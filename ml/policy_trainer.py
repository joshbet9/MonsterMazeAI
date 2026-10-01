#!/usr/bin/env python3
"""Train a long-horizon state/action return model from simulator rollouts."""
from __future__ import annotations
import argparse, json, math, random
from pathlib import Path
import numpy as np

FEATURE_COUNT = 44

def load_rows(path: Path):
    out = []
    with path.open("r", encoding="utf-8-sig") as h:
        for line in h:
            if not line.strip():
                continue
            r = json.loads(line)
            if isinstance(r.get("features"), list) and len(r["features"]) == FEATURE_COUNT \
                    and isinstance(r.get("reward"), (int, float)) \
                    and math.isfinite(float(r["reward"])) and r.get("episode"):
                out.append(r)
    return out

def add_returns(rows, gamma):
    groups = {}
    for r in rows:
        groups.setdefault(str(r["episode"]), []).append(r)
    out = []
    for group in groups.values():
        group.sort(key=lambda r: int(r.get("t", 0)))
        running = 0.0
        for r in reversed(group):
            running = float(r["reward"]) + gamma * running
            x = dict(r)
            x["return"] = running
            out.append(x)
    return out

def grouped_split(rows, fraction, seed):
    ids = sorted({str(r["episode"]) for r in rows})
    rng = random.Random(seed)
    rng.shuffle(ids)
    valid_ids = set(ids[:max(1, int(len(ids) * fraction))])
    return [r for r in rows if str(r["episode"]) not in valid_ids], [r for r in rows if str(r["episode"]) in valid_ids]

def prepare(rows):
    return np.asarray([r["features"] for r in rows], dtype=np.float64), np.asarray([r["return"] for r in rows], dtype=np.float64)

def standardise(train_x, valid_x):
    mean = train_x.mean(0)
    std = np.where(train_x.std(0) < 1e-8, 1.0, train_x.std(0))
    return (train_x-mean)/std, (valid_x-mean)/std, mean, std

def relu(x): return np.maximum(x, 0.0)

class MLP:
    def __init__(self, inp, h1, h2, seed):
        rng = np.random.default_rng(seed)
        self.w1 = (rng.standard_normal((inp,h1))*math.sqrt(2/inp)).astype(np.float64)
        self.b1 = np.zeros(h1); self.w2 = (rng.standard_normal((h1,h2))*math.sqrt(2/h1)).astype(np.float64)
        self.b2 = np.zeros(h2); self.w3 = (rng.standard_normal((h2,1))*math.sqrt(2/h2)).astype(np.float64)
        self.b3 = np.zeros(1)
    def forward(self,x):
        z1=x@self.w1+self.b1; a1=relu(z1); z2=a1@self.w2+self.b2; a2=relu(z2); y=a2@self.w3+self.b3
        return y[:,0],(x,z1,a1,z2,a2)
    def gradients(self,c,dy):
        x,z1,a1,z2,a2=c; n=x.shape[0]; d=dy.reshape(-1,1)/n
        dw3=a2.T@d; db3=d.sum(0); dz2=(d@self.w3.T)*(z2>0); dw2=a1.T@dz2; db2=dz2.sum(0)
        dz1=(dz2@self.w2.T)*(z1>0); dw1=x.T@dz1; db1=dz1.sum(0)
        return dw1,db1,dw2,db2,dw3,db3
    def step(self,grads,state,t,lr):
        for key,g in zip(("w1","b1","w2","b2","w3","b3"),grads):
            m=state[key+"_m"]; v=state[key+"_v"]; m[:]=0.9*m+0.1*g; v[:]=0.999*v+0.001*g*g
            mh=m/(1-0.9**t); vh=v/(1-0.999**t); getattr(self,key)[:] -= lr*mh/(np.sqrt(vh)+1e-8)

def adam_state(model):
    out={}
    for k in ("w1","b1","w2","b2","w3","b3"):
        v=getattr(model,k); out[k+"_m"]=np.zeros_like(v); out[k+"_v"]=np.zeros_like(v)
    return out

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--input",required=True); p.add_argument("--output",required=True)
    p.add_argument("--epochs",type=int,default=80); p.add_argument("--batch-size",type=int,default=512)
    p.add_argument("--samples-per-epoch",type=int,default=50000); p.add_argument("--hidden1",type=int,default=48)
    p.add_argument("--hidden2",type=int,default=24); p.add_argument("--learning-rate",type=float,default=0.001)
    p.add_argument("--gamma",type=float,default=0.9995); p.add_argument("--validation-fraction",type=float,default=0.2)
    p.add_argument("--min-samples",type=int,default=500); p.add_argument("--seed",type=int,default=1337)
    p.add_argument("--report-every",type=int,default=10); a=p.parse_args()

    raw=load_rows(Path(a.input))
    if len(raw)<a.min_samples: raise SystemExit(f"Need at least {a.min_samples} usable rows; found {len(raw)}")
    rows=add_returns(raw,a.gamma)
    train,valid=grouped_split(rows,a.validation_fraction,a.seed)
    tx,ty=prepare(train); vx,vy=prepare(valid); tx,vx,mean,std=standardise(tx,vx)
    target_mean=float(ty.mean()); target_std=max(float(ty.std()),1e-8)
    model=MLP(FEATURE_COUNT,a.hidden1,a.hidden2,a.seed); adam=adam_state(model)
    ty_n=(ty-target_mean)/target_std
    rng=np.random.default_rng(a.seed); order=np.arange(len(train))
    for epoch in range(1,a.epochs+1):
        rng.shuffle(order); active=order[:min(len(order),a.samples_per_epoch)]
        for start in range(0,len(active),a.batch_size):
            b=active[start:start+a.batch_size]; pred,c=model.forward(tx[b])
            model.step(model.gradients(c,2*(pred-ty_n[b])),adam,epoch,a.learning_rate)
        if epoch==1 or epoch%a.report_every==0 or epoch==a.epochs:
            pn,_=model.forward(vx); pp=pn*target_std+target_mean
            print(f"epoch={epoch:4d} validation_mae={np.mean(np.abs(vy-pp)):.4f} validation_mse={np.mean((vy-pp)**2):.4f}")

    trn,_=model.forward(tx); van,_=model.forward(vx); trp=trn*target_std+target_mean; vap=van*target_std+target_mean
    payload={"version":1,"objective":"long_horizon_policy_return",
      "architecture":[FEATURE_COUNT,a.hidden1,a.hidden2,1],
      "feature_names":["stage_norm","health_ratio","horizontal_speed","forward_speed","lateral_speed","vertical_speed","grounded","phase_ticks_norm","ability_charges_norm","ability_active_norm","pad_distance_norm","pad_direction_cos","pad_direction_sin","old_pad_count_norm","preview_pad_distance_norm","local_floor_north","local_floor_south","local_floor_east","local_floor_west","local_floor_northeast","local_floor_northwest","local_floor_southeast","local_floor_southwest","mode_speed","mode_modern","kit_jumper","kit_maverick","kit_slowballer","kit_repulsor","kit_body_builder","monster_count_12_norm","monster_count_20_norm","nearest_monster_distance_norm","nearest_monster_closing_norm","nearest_monster_forward_norm","nearest_monster_lateral_norm","max_monster_closing_norm","min_time_to_contact_norm","action_forward","action_strafe","action_jump","action_sprint","action_yaw_delta","action_ability"],
      "gamma":a.gamma,"input_mean":mean.tolist(),"input_std":std.tolist(),"target_mean":target_mean,"target_std":target_std,
      "target_min":float(ty.min()),"target_max":float(ty.max()),
      "w1":model.w1.tolist(),"b1":model.b1.tolist(),"w2":model.w2.tolist(),"b2":model.b2.tolist(),"w3":model.w3[:,0].tolist(),"b3":model.b3.tolist(),
      "metrics":{"rows":len(rows),"episodes":len({str(r["episode"]) for r in rows}),"train_rows":len(train),"validation_rows":len(valid),
      "train_mae":float(np.mean(np.abs(ty-trp))),"validation_mae":float(np.mean(np.abs(vy-vap))),
      "train_mse":float(np.mean((ty-trp)**2)),"validation_mse":float(np.mean((vy-vap)**2))}}
    Path(a.output).parent.mkdir(parents=True,exist_ok=True); Path(a.output).write_text(json.dumps(payload,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(payload["metrics"],indent=2))

if __name__=="__main__": main()
