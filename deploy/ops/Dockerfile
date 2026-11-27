FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /ops
COPY requirements.txt .
RUN pip install -r requirements.txt
COPY ops_cli.py service_catalog.json .

ENTRYPOINT ["python", "/ops/ops_cli.py"]
