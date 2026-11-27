"""Optional DistilBERT fine-tuning; requires a pretrained model and extra dependencies."""
import argparse
import json
from pathlib import Path
import pandas as pd
from sklearn.metrics import classification_report
from .issues import generate, split_data

def run(out, model_name, data=None, epochs=3, seed=42, local_only=False):
    import torch
    from transformers import AutoTokenizer, AutoModelForSequenceClassification
    torch.manual_seed(seed); torch.set_num_threads(min(4,torch.get_num_threads()))
    df=generate() if data is None else pd.read_csv(data); train,val,test=split_data(df)
    labels=sorted(df.label.unique()); ids={label:i for i,label in enumerate(labels)}
    tokenizer=AutoTokenizer.from_pretrained(model_name,local_files_only=local_only)
    model=AutoModelForSequenceClassification.from_pretrained(model_name,num_labels=len(labels),id2label=dict(enumerate(labels)),label2id=ids,local_files_only=local_only)
    device=torch.device('cuda' if torch.cuda.is_available() else 'cpu');model.to(device)
    opt=torch.optim.AdamW(model.parameters(),lr=2e-5)
    def batch(frame):
        x=tokenizer(frame.text.tolist(),padding=True,truncation=True,max_length=128,return_tensors='pt').to(device)
        return x,torch.tensor([ids[y] for y in frame.label],device=device)
    def evaluate(frame):
        model.eval();pred=[]
        with torch.no_grad():
            for start in range(0,len(frame),8):
                x,_=batch(frame.iloc[start:start+8]);pred.extend(model(**x).logits.argmax(-1).cpu().tolist())
        return [labels[i] for i in pred]
    best=None;best_f1=-1
    for epoch in range(epochs):
        model.train();shuffled=train.sample(frac=1,random_state=seed+epoch)
        for start in range(0,len(shuffled),8):
            x,y=batch(shuffled.iloc[start:start+8]);opt.zero_grad();loss=model(**x,labels=y).loss;loss.backward();opt.step()
        f1=classification_report(val.label,evaluate(val),labels=labels,output_dict=True,zero_division=0)['macro avg']['f1-score']
        if f1>best_f1:
            best_f1=f1;best={k:v.detach().cpu().clone() for k,v in model.state_dict().items()}
    model.load_state_dict(best);pred=evaluate(test)
    out=Path(out);out.mkdir(parents=True,exist_ok=True)
    report={'model':model_name,'seed':seed,'epochs':epochs,'validation_macro_f1':best_f1,'test':classification_report(test.label,pred,labels=labels,output_dict=True,zero_division=0)}
    (out/'distilbert_metrics.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    test.assign(distilbert_prediction=pred).to_csv(out/'distilbert_predictions.csv',index=False)
    model.save_pretrained(out/'distilbert_model');tokenizer.save_pretrained(out/'distilbert_model');return report

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);p.add_argument('--model',default='distilbert/distilbert-base-uncased');p.add_argument('--data',type=Path);p.add_argument('--epochs',type=int,default=3);p.add_argument('--local-only',action='store_true');a=p.parse_args()
    if a.epochs<1:p.error('--epochs must be positive')
    print(json.dumps(run(a.out,a.model,a.data,a.epochs,local_only=a.local_only),indent=2))
