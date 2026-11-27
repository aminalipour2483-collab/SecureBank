# SecureBank AI Research Lab

This offline research extension calls the same Java graph engine used by the banking application. It does not connect to MySQL, alter customer records or change the live fraud thresholds. All supplied examples and results are fictional.

## Reproduce

From the repository root, resolve dependencies and build the Java applications as described in the main README. Then export independent graph snapshots:

```bash
java -cp 'dist/AdminBank.jar:dist/lib/*' com.bank.tools.SyntheticGraphBenchmark research/run/graph_features.csv 42
```

Windows PowerShell / Command Prompt:

```powershell
java -cp "dist/AdminBank.jar;dist/lib/*" com.bank.tools.SyntheticGraphBenchmark research/run/graph_features.csv 42
```

From `research/`, use Python 3.11–3.13 and a clean virtual environment:

```bash
python -m venv .venv
# Linux/macOS: source .venv/bin/activate
# Windows PowerShell: .venv\Scripts\Activate.ps1
python -m pip install -r requirements-deep.txt
python -m pytest -q
python -m securebank_ai.anomaly --data run/graph_features.csv --out run
python -m securebank_ai.issues --out run --predict "The login screen rejects a valid PIN"
```

For a lightweight run without PyTorch, install `requirements.txt` and add `--skip-torch` to the anomaly command. The committed `results/` directory contains one actual CPU run at seed 42. New runs go into the ignored `run/` directory. On Windows a CPU build of PyTorch can be installed using its official package index if needed.

## A. Graph anomaly experiment

The Java CLI constructs eight independent graphs, each with 250 fictional customers, including 24 planted anomaly participants (12 ring members, six high-velocity senders, six collectors) and 18 benign high-activity customers. Background directed edges follow a randomly arranged acyclic order with ring members grouped in blocks to bound cycle enumeration. Ring edges and high-volume flows are injected on top. This is a stylized generator, not a financial transaction simulator; edges are aggregated counts and amounts. Customer IDs are local to each graph. All 16 features come directly from `GraphAnomalyDetector.featureVectors`; `java_if`, `java_risk` and reason codes come from the existing Java scoring engines. Labels and scenario names are never model features.

- Graphs 0–3: training (1,000 rows); 4–5: validation (500); 6–7: test (500, 48 positives).
- Existing Java Isolation Forest is **transductive**: it fits independently to the unlabeled feature vectors of each current graph, including test graphs. The Java hybrid score includes its existing anomaly rule and other heuristics. Python models fit on separate training graphs; their ranking comparisons are therefore not identical learning protocols.
- Scikit-learn Isolation Forest fits on the unlabeled training features. PyTorch autoencoder (16→24→6→24→16, ReLU, MSE, Adam, 80 epochs) fits only known-normal training rows. The latter is one-class learning with label assistance, not a purely unsupervised comparison.
- StandardScaler fits on training features only. New thresholds are the 95th percentile of normal validation scores. The existing Java thresholds (0.65 and HIGH risk >=50) are also reported unchanged. Ties can cause the false-positive rate to exceed 5%. The test labels are used only for final evaluation; thresholds are not applied to the live banking UI.
- Report: precision, recall, F1, average precision, ROC AUC, confusion counts, precision–recall curves and row-level predictions. The calibrated and fixed variants share rankings and therefore identical AP/AUC.

### Limitations

The generator has artificial graph topology and planted behaviors, only eight independent snapshots and one recorded random seed. Graph features can exploit these synthetic patterns. There are no real fraud labels, temporal holdouts or external validation. AUC is ranking quality, not probability calibration. A normal participant can be adjacent to an injected pattern and receive a high score. Results are descriptive, not a statistical claim of superiority. More seeds, realistic benign cycles, overlapping behaviors, realistic temporal data, and an external dataset would be necessary for stronger claims. Never automatically ban customers using these scores.

## B. NLP for software engineering: issue classification

`securebank_ai.issues` categorizes fictional SecureBank issue reports into authentication, database, UI and fraud detection. It creates 64 short authored texts in 32 scenario groups, using 40 train, 8 validation, 16 test examples. Both paraphrases of a scenario are kept together. TF-IDF + logistic regression and multinomial Naive Bayes are fitted on training only; report macro/weighted precision, recall, F1 and confusion matrices. The optional `--predict` suggests a category for human review. These are tiny hand-authored examples, not real GitHub issues. Do not claim production triage accuracy.

A larger labeled CSV may be passed with `--data path.csv`. Columns: `group,split,label,text`. Assign related issue descriptions the same group, and split before training. Use `train`, `validation`, `test`; every label must appear in each. Do not include secrets, identifiers or raw customer logs. Output contains the supplied text, so keep private runs out of Git.

### Optional DistilBERT fine-tuning

```bash
python -m pip install -r requirements-transformer.txt
python -m securebank_ai.transformer --out run --epochs 3
```

The implementation tokenizes reports, fine-tunes a sequence classification head and pretrained encoder, selects the checkpoint by validation macro-F1, then evaluates the held-out test set once and exports metrics, predictions and a local model. Use `--model /path/to/local/pretrained/model --local-only` for offline pretrained weights. Optional dependencies and model weights are not included. **This transformer pipeline was syntax-checked, not executed; no transformer results or weights are claimed.** Use the same grouped dataset for a fair comparison. PyTorch autoencoder and both classical NLP baselines were actually executed.

## Reliability

The Python tests exercise split leakage, duplicates, non-finite features, truncated cycle rejection, metrics and end-to-end baseline execution. Java regression suites continue to test fault injection, authentication, JDBC and graph scoring. GitHub Actions includes an offline research workflow using classical baselines (no model downloads). This is an offline research module, not a production deployment or live service.
