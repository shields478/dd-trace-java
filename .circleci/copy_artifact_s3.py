import boto3
import re
import os
import tempfile


S3_BUCKET_NAME = 'datadog-reliability-env'
client = boto3.client('s3', aws_access_key_id=os.getenv('AWS_ACCESS_KEY_ID'),aws_secret_access_key=os.getenv('AWS_SECRET_ACCESS_KEY'))
transfer = boto3.s3.transfer.S3Transfer(client)

p = re.compile(r"dd-java-agent.*jar$")
LIBS_PATH = './workspace/dd-java-agent/build/libs'
for path, sub_dirs, files in os.walk(LIBS_PATH):
    for name in files:
      if p.match(name):
        # Write the artifact to S3
        transfer.upload_file(os.path.join(path, name), S3_BUCKET_NAME, f'java/{name}')
        # write additional information used by the build
        with tempfile.NamedTemporaryFile(mode='w') as fp:
          fp.write(f"{os.getenv('CIRCLE_BRANCH')}\n")
          fp.write(f"{os.getenv('CIRCLE_SHA1')}\n")
          fp.write(f"{name}\n")
          fp.write(f"{os.getenv('CIRCLE_USERNAME')}\n")
          fp.seek(0)
          transfer.upload_file(fp.name, S3_BUCKET_NAME, 'java/index.txt')
