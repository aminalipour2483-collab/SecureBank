"""Offline comparison on independent synthetic Java graph snapshots."""
import argparse
import json
from pathlib import Path
import numpy as np
import pandas as pd
from sklearn.ensemble import IsolationForest
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import (precision_score, recall_score, f1_score, average_precision_score,
                             roc_auc_score, confusion_matrix, PrecisionRecallDisplay)
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

FEATURES = 'log_out_count,log_in_count,log_out_amount,log_in_amount,log_out_neighbors,log_in_neighbors,log_reciprocal,log_shared_phones,log_accounts,log_age,age_missing,betweenness,log_pagerank,log_cycles,community_density,log_max_pair_count'.split(',')

def load_graph(path):
    df = pd.read_csv(path)
    required = set(FEATURES + ['snapshot','split','customer_id','label','java_if','java_risk','scenario','cycle_truncated'])
    if not required <= set(df):
        raise ValueError(f'Missing columns: {required - set(df)}')
    if df.duplicated(['snapshot','customer_id']).any() or not np.isfinite(df[FEATURES+['java_if','java_risk']]).all().all():
        raise ValueError('Duplicate or non-finite rows')
    if set(df.split) != {'train','validation','test'} or df.groupby('snapshot').split.nunique().max() != 1:
        raise ValueError('Snapshot leakage or missing split')
    if not df.label.isin([0,1]).all() or any(set(d.label)!={0,1} for _,d in df.groupby('split')):
        raise ValueError('Each split needs binary labels and both classes')
    if df.cycle_truncated.astype(str).str.lower().ne('false').any():
        raise ValueError('Incomplete cycle enumeration: benchmark refuses truncated graphs')
    return df

def metrics(y, scores, threshold):
    pred = np.asarray(scores) >= threshold
    tn, fp, fn, tp = confusion_matrix(y, pred, labels=[0,1]).ravel()
    return dict(threshold=float(threshold), precision=float(precision_score(y,pred,zero_division=0)),
                recall=float(recall_score(y,pred,zero_division=0)), f1=float(f1_score(y,pred,zero_division=0)),
                average_precision=float(average_precision_score(y,scores)), roc_auc=float(roc_auc_score(y,scores)),
                tn=int(tn),fp=int(fp),fn=int(fn),tp=int(tp))

def autoencoder(train, val, test, seed):
    import torch
    from torch import nn
    torch.manual_seed(seed); torch.set_num_threads(1)
    torch.use_deterministic_algorithms(True)
    model = nn.Sequential(nn.Linear(train.shape[1],24),nn.ReLU(),nn.Linear(24,6),nn.ReLU(),
                          nn.Linear(6,24),nn.ReLU(),nn.Linear(24,train.shape[1]))
    optimizer = torch.optim.Adam(model.parameters(),lr=0.003)
    x = torch.tensor(train,dtype=torch.float32)
    for _ in range(80):
        for batch in x[torch.randperm(len(x))].split(64):
            loss=(model(batch)-batch).square().mean()
            optimizer.zero_grad(); loss.backward(); optimizer.step()
    model.eval()
    def score(a):
        with torch.no_grad():
            t=torch.tensor(a,dtype=torch.float32)
            return (model(t)-t).square().mean(dim=1).numpy()
    return score(val),score(test)

def run(path, out, seed=42, include_torch=True):
    df=load_graph(path); out=Path(out); out.mkdir(parents=True,exist_ok=True)
    train,val,test=[df[df.split==s].copy() for s in ['train','validation','test']]
    scaler=StandardScaler().fit(train[FEATURES]); a,b,c=[scaler.transform(d[FEATURES]) for d in (train,val,test)]
    forest=IsolationForest(n_estimators=100,max_samples=min(256,len(a)),random_state=seed,n_jobs=1).fit(a)
    candidates={'java_isolation_forest':(val.java_if.to_numpy(),test.java_if.to_numpy(),0.65),
                'java_hybrid_risk':(val.java_risk.to_numpy(),test.java_risk.to_numpy(),50),
                'java_if_calibrated':(val.java_if.to_numpy(),test.java_if.to_numpy(),None),
                'java_hybrid_calibrated':(val.java_risk.to_numpy(),test.java_risk.to_numpy(),None),
                'sklearn_isolation_forest':(-forest.score_samples(b),-forest.score_samples(c),None)}
    if include_torch:
        # One-class training uses known-normal synthetic labels; not purely unsupervised.
        av,at=autoencoder(a[train.label.to_numpy()==0],b,c,seed)
        candidates['pytorch_autoencoder']=(av,at,None)
    report={'seed':seed,'data':'synthetic; no real financial data','rows':{s:int((df.split==s).sum()) for s in ['train','validation','test']},
            'threshold_policy':'Existing Java thresholds plus offline calibrated Java and Python: 95th percentile of validation-normal scores (ties can exceed 5% FPR)', 'models':{}}
    predictions=test[['snapshot','customer_id','label','scenario']].copy()
    fig,ax=plt.subplots(figsize=(7,5))
    for name,(v,t,fixed) in candidates.items():
        threshold=fixed if fixed is not None else float(np.quantile(v[val.label.to_numpy()==0],.95))
        report['models'][name]=metrics(test.label,t,threshold)
        predictions[name+'_score']=t; predictions[name+'_flag']=(t>=threshold).astype(int)
        if not name.endswith("_calibrated"):
            PrecisionRecallDisplay.from_predictions(test.label,t,name=name,ax=ax)
    ax.set_title('Held-out synthetic graph snapshots'); fig.tight_layout(); fig.savefig(out/'anomaly_pr.png',dpi=150); plt.close(fig)
    predictions.to_csv(out/'anomaly_predictions.csv',index=False)
    (out/'anomaly_metrics.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    return report

if __name__=='__main__':
    p=argparse.ArgumentParser(); p.add_argument('--data',type=Path,required=True); p.add_argument('--out',type=Path,required=True); p.add_argument('--seed',type=int,default=42); p.add_argument('--skip-torch',action='store_true'); args=p.parse_args()
    print(json.dumps(run(args.data,args.out,args.seed,not args.skip_torch),indent=2))
