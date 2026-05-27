# Running the Benchmark on EC2

The local-Mac numbers in the report are useful for development and SF=10
publication. For SF=100 (~100 GB lineitem) and the canonical
"single-box headline" numbers Spark committers expect, use a single
EC2 `m6i.8xlarge` instance.

Why this size: 32 vCPUs (Intel Ice Lake, AVX-512), 128 GB RAM, 12.5 Gbps
network. Fits a full SF=100 working set in RAM with headroom for the
JVM heap. Hourly cost ~$1.54/hr; a full benchmark sweep at SF=100
takes ~90 minutes — about $2.50 per publication-quality run.

## Instance setup

```sh
# Launch an m6i.8xlarge with Ubuntu 24.04 LTS (amd64), 500 GB gp3 root.
# Recommended AMI (us-east-1): ami-053b0d53c279acc90 — Canonical, Ubuntu, 24.04 LTS, amd64.
# (Use https://cloud-images.ubuntu.com/locator/ec2/ for your region.)

# SSH in.
ssh -i ~/.ssh/your-key.pem ubuntu@<public-ip>

# Update + install JDK 21 + build tools.
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk git make gcc unzip

# Install sbt 1.12 (Coursier route — no sudo needed afterward).
curl -fLo cs https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-linux.gz
gunzip cs && chmod +x cs
./cs setup -y
source ~/.profile
sbt --version   # confirm 1.12.x

# Clone the repo at the commit you want to benchmark.
git clone https://github.com/<org>/ProtoCatalyst.git
cd ProtoCatalyst
git checkout <commit-sha>

# Prime sbt's dependency cache and verify the encoder-spark tests pass.
sbt 'encoderSpark/test'
```

## Running the benchmark

```sh
# Full publication run at SF=100. Takes ~90 minutes total.
./scripts/bench.sh 100

# Results land in results/<timestamp>-sf100/:
#   disclosure.txt                full hardware/JVM/git disclosure
#   micro-protocatalyst.json      JMH JSON for our serializer
#   micro-spark.json              JMH JSON for Spark's encoder
#   queries.csv                   end-to-end query timings
```

Copy results back to your local machine:

```sh
# From your laptop, after the cloud run finishes:
scp -i ~/.ssh/your-key.pem -r ubuntu@<public-ip>:~/ProtoCatalyst/results/<ts>-sf100 ./
```

## What to verify in the disclosure

Before publishing any number from the cloud run:

- `disclosure.txt` shows JDK 21, `os=Linux`, `arch=x86_64`, `cores=32`,
  `RAM=124+ GB`. If any of these differ, document the divergence.
- Git SHA matches the commit you intend to publish.
- JMH JSON files contain non-zero scores for every benchmark.
- `queries.csv` has 0 `error` rows.

## Cost discipline

- Stop the instance immediately after copying results — m6i.8xlarge
  bills per second, but at $1.54/hr leaving it idle overnight is $37.
- Use spot instances if you're tolerant of mid-run pre-emption — about
  60% cheaper, but a 90-minute run that gets killed at minute 89 is
  expensive in time. Recommend on-demand for the benchmark itself.

## Cross-checking with Photon-paper-style cluster runs

This doc only covers single-node. A 4-node m6i.4xlarge cluster
(or one r6i.4xlarge driver + four r6i.4xlarge workers) is the next
step up if a reviewer asks for shuffle-heavy SF=1000 numbers. That's
Phase C / future work; the encoder narrative we're publishing now
doesn't require it.
