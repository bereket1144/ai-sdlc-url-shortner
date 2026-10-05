# The image that builds and tests a workspace (sandbox.verify.image). Build it once, from the repository root:
#   docker build -t agent-sandbox-python docker/sandbox-python
FROM python:3.12-slim
RUN pip install --no-cache-dir pytest==8.3.4 && useradd --uid 10001 --no-create-home sandbox
USER sandbox
