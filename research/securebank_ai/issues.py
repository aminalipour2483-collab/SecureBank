"""Synthetic SecureBank issue triage: grouped splits and classical NLP baselines."""
import argparse
import json
from pathlib import Path
import pandas as pd
from sklearn.pipeline import make_pipeline
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.naive_bayes import MultinomialNB
from sklearn.metrics import classification_report, confusion_matrix, ConfusionMatrixDisplay
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

# Authored fictional reports, not copied from real users or production logs.
# Each group represents one issue scenario. Its paraphrases stay in one partition.
SCENARIOS={
 'authentication':[
 ('Login rejects correct PIN','Valid PIN cannot sign in'),('Account lockout never expires','Locked user stays blocked'),('Password reset token rejected','Recovery token fails verification'),('Admin session not cleared on logout','Administrator remains signed in'),('Login throttling ignores failed attempts','Repeated invalid passwords are not limited'),('BCrypt verification fails after registration','New password hash is not accepted'),('Customer recovery changes wrong account','PIN reset affects another identity'),('Expired session can still open protected screen','Authentication guard accepts old session')],
 'database':[
 ('Transfer rollback leaves debit committed','Failed transaction still deducts balance'),('Connection pool runs out of JDBC connections','Database handles are not returned to pool'),('SQL migration missing required column','Schema upgrade fails on absent field'),('Duplicate idempotency key creates second transfer','Retry writes another transaction row'),('Deadlock during concurrent balance updates','Parallel SQL operations wait on locks'),('Prepared statement parameter order is wrong','Query binds the wrong value'),('Database timeout prevents transaction commit','JDBC commit operation times out'),('MySQL foreign key rejects account deletion','SQL constraint prevents deleting account')],
 'ui':[
 ('Customer window freezes during registration','Swing form becomes unresponsive'),('Graph labels overlap after resize','Node captions collide in resized panel'),('Transaction history table does not refresh','Displayed rows stay stale'),('Persian text clipped in dialog','Right-to-left caption is cut off'),('Progress spinner remains after worker finishes','Loading indicator never disappears'),('Admin review button stays disabled','Investigation control cannot be clicked'),('Graph zoom moves nodes off canvas','Canvas viewport loses diagram'),('Error dialog opens behind main window','Popup is hidden by Swing frame')],
 'fraud_detection':[
 ('Isolation Forest scores ordinary customers too high','Anomaly model flags normal behavior'),('Money cycle detector misses circular transfers','Directed ring not found in graph'),('Risk explanation omits triggered velocity rule','Score reasons lack transfer frequency signal'),('PageRank ranking changes for identical snapshot','Centrality output is not deterministic'),('Small baseline incorrectly activates anomaly detector','Insufficient peers still get anomaly scores'),('Shared phone signal duplicates risk points','Graph risk counts phone link twice'),('Community grouping splits known synthetic ring','Graph clustering separates planted ring'),('Extreme amount distorts anomaly ranking','Outlier detector misorders unusual amounts')]
}

def generate():
    rows=[]
    for label,groups in SCENARIOS.items():
        for i,variants in enumerate(groups):
            split='train' if i<5 else 'validation' if i==5 else 'test'
            for j,text in enumerate(variants):
                rows.append(dict(group=f'{label}-{i}',split=split,label=label,text=text))
    return pd.DataFrame(rows)

def validate(df):
    if not {'group','split','label','text'}<=set(df) or df[['group','split','label','text']].isna().any().any(): raise ValueError('Invalid issue dataset')
    if set(df.split)!={'train','validation','test'} or df.groupby('group').split.nunique().max()!=1: raise ValueError('Group leakage or missing split')
    if df.text.str.casefold().str.strip().duplicated().any(): raise ValueError('Duplicate issue text')
    labels=set(df.label)
    if any(set(d.label)!=labels for _,d in df.groupby('split')): raise ValueError('All classes must occur in every split')
    return df

def split_data(df):
    validate(df)
    return [df[df.split==s] for s in ('train','validation','test')]

def pipeline(classifier):
    return make_pipeline(TfidfVectorizer(ngram_range=(1,2),sublinear_tf=True),classifier)

def run(out, data=None, predict=None):
    out=Path(out);out.mkdir(parents=True,exist_ok=True)
    df=generate() if data is None else pd.read_csv(data); train,val,test=split_data(df)
    labels=sorted(df.label.unique()); report={'data':'authored synthetic examples' if data is None else 'user-supplied grouped CSV','rows':df.split.value_counts().to_dict(),'models':{}}
    df.to_csv(out/'issues.csv',index=False); results=test.copy()
    for name,cls in [('tfidf_logistic',LogisticRegression(max_iter=1000,random_state=42)),('tfidf_naive_bayes',MultinomialNB())]:
        model=pipeline(cls).fit(train.text,train.label)
        pred=model.predict(test.text); results[name]=pred
        report['models'][name]=classification_report(test.label,pred,labels=labels,output_dict=True,zero_division=0)
        fig,ax=plt.subplots(figsize=(7,5)); ConfusionMatrixDisplay(confusion_matrix(test.label,pred,labels=labels),display_labels=labels).plot(ax=ax,xticks_rotation=30,colorbar=False); fig.tight_layout();fig.savefig(out/(name+'_confusion.png'),dpi=150);plt.close(fig)
        if predict and name=='tfidf_logistic': print('Suggested category (human review required):',model.predict([predict])[0])
    results.to_csv(out/'issue_predictions.csv',index=False)
    (out/'issue_metrics.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');return report

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);p.add_argument('--data',type=Path);p.add_argument('--predict');a=p.parse_args();print(json.dumps(run(a.out,a.data,a.predict),indent=2))
