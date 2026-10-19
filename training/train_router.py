"""Train an actual small intent classifier, NOT the chat LLM.
Run: python -m pip install -r training/requirements.txt && python training/train_router.py
"""
import json,re,hashlib,platform
from pathlib import Path
from collections import Counter
import numpy as np
import sklearn
from sklearn.feature_extraction import DictVectorizer
from sklearn.feature_extraction.text import TfidfTransformer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score,confusion_matrix
ROOT=Path(__file__).resolve().parents[1]
def features(text):
 out=Counter()
 for word in re.findall(r'[a-z0-9\u0600-\u06ff]+',text.lower()):
  out['w:'+word]+=1
  for n in (2,3):
   for i in range(len(word)-n+1):out['c:'+word[i:i+n]]+=1
 return {k:min(v,3) for k,v in out.items()}
def read(name):return [json.loads(l) for l in (ROOT/'training'/name).read_text().splitlines()]
def main():
 train,ev=read('train.jsonl'),read('eval.jsonl')
 assert not ({x['text'].lower() for x in train}&{x['text'].lower() for x in ev})
 vec=DictVectorizer();tf=TfidfTransformer();X=tf.fit_transform(vec.fit_transform([features(x['text']) for x in train]));y=[x['label'] for x in train]
 model=LogisticRegression(C=8,max_iter=1000,random_state=42).fit(X,y)
 ex=tf.transform(vec.transform([features(x['text']) for x in ev]));probs=model.predict_proba(ex);pred=model.predict(ex);truth=[x['label'] for x in ev]
 # Conservative abstention: never assert a confident route for weak or ambiguous input.
 threshold=.45; margin=.12
 routed=[str(model.classes_[p.argmax()]) if p.max()>=threshold and sorted(p)[-1]-sorted(p)[-2]>=margin else 'general' for p in probs]
 report={'model':'KUN task router v1','kind':'TF-IDF + multiclass logistic regression; not LLM fine-tuning','train_examples':len(train),'heldout_examples':len(ev),'synthetic_dataset':True,'seed':42,'features':len(vec.feature_names_),'classes':model.classes_.tolist(),'raw_accuracy':accuracy_score(truth,pred),'with_abstention_accuracy':accuracy_score(truth,routed),'confusion_matrix':confusion_matrix(truth,pred,labels=model.classes_).tolist(),'limitations':['Small synthetic authored test set; not an independent benchmark.','Intent classification only; does not measure answer quality or general intelligence.','No chat-model weight updates.'],'sklearn_version':sklearn.__version__,'python_version':platform.python_version(),'train_sha256':hashlib.sha256((ROOT/'training/train.jsonl').read_bytes()).hexdigest(),'eval_sha256':hashlib.sha256((ROOT/'training/eval.jsonl').read_bytes()).hexdigest()}
 payload={'version':1,'threshold':threshold,'margin':margin,'features':vec.feature_names_,'idf':tf.idf_.tolist(),'classes':model.classes_.tolist(),'weights':model.coef_.tolist(),'bias':model.intercept_.tolist(),'training':report}
 (ROOT/'app/src/main/assets/task-router.json').write_text(json.dumps(payload,ensure_ascii=False,separators=(',',':')))
 (ROOT/'training/report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
 fixtures=[{'text':e['text'],'probabilities':p.tolist(),'route':r} for e,p,r in zip(ev,probs,routed)]
 (ROOT/'tests/router-fixtures.json').write_text(json.dumps(fixtures,ensure_ascii=False))
 print(json.dumps(report,indent=2))
if __name__=='__main__':main()
