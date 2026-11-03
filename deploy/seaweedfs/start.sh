#!/bin/sh
# Starts SeaweedFS with its S3 gateway and one access key taken from the environment.
set -eu
cat > /tmp/s3.json <<JSON
{"identities":[{"name":"nexora",
  "credentials":[{"accessKey":"${S3_ACCESS_KEY}","secretKey":"${S3_SECRET_KEY}"}],
  "actions":["Admin","Read","Write","List","Tagging"]}]}
JSON
exec weed server -dir=/data -ip=s3 -ip.bind=0.0.0.0 -s3 -s3.port=8333 -s3.config=/tmp/s3.json
