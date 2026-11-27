import numpy as np
import pandas as pd
import pytest
from securebank_ai.anomaly import FEATURES, load_graph, metrics, run
from securebank_ai.issues import generate, validate, run as issue_run


def graph_data():
    rows=[]
    for snap,split in enumerate(['train','validation','test']):
        for i in range(40):
            y=int(i>=35); row=dict(snapshot=snap,split=split,customer_id=i+1,label=y,java_if=.8 if y else .3,java_risk=80 if y else 15,scenario='ring' if y else 'ordinary',cycle_truncated=False)
            row.update({f:float(i+j+10*y) for j,f in enumerate(FEATURES)});rows.append(row)
    return pd.DataFrame(rows)


def write(df,path):
    df.to_csv(path,index=False);return path


def test_metrics_counts():
    m=metrics([0,0,1,1],[.1,.8,.9,.2],.5)
    assert (m['tn'],m['fp'],m['fn'],m['tp'],m['f1'])==(1,1,1,1,.5)


def test_snapshot_leakage(tmp_path):
    df=graph_data();df.loc[0,'split']='test'
    with pytest.raises(ValueError,match='leakage'):load_graph(write(df,tmp_path/'d.csv'))


def test_duplicate_customers(tmp_path):
    df=graph_data();df=pd.concat([df,df.iloc[[0]]])
    with pytest.raises(ValueError,match='Duplicate'):load_graph(write(df,tmp_path/'d.csv'))


def test_nonfinite_features(tmp_path):
    df=graph_data();df.loc[0,FEATURES[0]]=np.inf
    with pytest.raises(ValueError,match='non-finite'):load_graph(write(df,tmp_path/'d.csv'))


def test_truncated_cycles(tmp_path):
    df=graph_data();df.loc[0,'cycle_truncated']=True
    with pytest.raises(ValueError,match='truncated'):load_graph(write(df,tmp_path/'d.csv'))


def test_anomaly_pipeline(tmp_path):
    report=run(write(graph_data(),tmp_path/'d.csv'),tmp_path/'out',include_torch=False)
    assert report['models']['java_isolation_forest']['f1']==1
    assert (tmp_path/'out/anomaly_predictions.csv').exists()
    assert report['rows']['test']==40


def test_issue_groups():
    df=validate(generate());assert len(df)==64
    assert df.groupby('group').split.nunique().max()==1


def test_issue_group_leakage():
    df=generate();df.loc[0,'split']='test'
    with pytest.raises(ValueError,match='leakage'):validate(df)


def test_issue_duplicate():
    df=generate();df.loc[1,'text']=df.loc[0,'text']
    with pytest.raises(ValueError,match='Duplicate'):validate(df)


def test_issue_pipeline(tmp_path):
    report=issue_run(tmp_path)
    assert len(report['models'])==2
    assert len(pd.read_csv(tmp_path/'issue_predictions.csv'))==16
