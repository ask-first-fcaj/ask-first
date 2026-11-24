# Image Lambda `ai` (BE-06). Build từ GỐC repo (cần prompts/, kb/):
#   docker build -f ai/Dockerfile -t askfirst-ai .
# Build trên GitHub Actions (.github/workflows/ai-image.yml), không build trên máy cá nhân.
# Giữ đúng cây thư mục của repo dưới /var/task vì code tìm prompts/, kb/, ai/config/ theo đường dẫn tương đối với file .py.
# Model OCR, VietOCR, embedder KHÔNG nằm trong image: S3ArtifactStore tải lười từ S3 về /tmp lúc chạy.
FROM public.ecr.aws/lambda/python:3.12

COPY ai/pyproject.toml ai/uv.lock /tmp/build/
RUN pip install --no-cache-dir uv \
 && uv export --project /tmp/build --frozen --no-dev --no-emit-project --no-hashes -o /tmp/build/requirements.txt \
 && uv pip install --no-cache --target "${LAMBDA_TASK_ROOT}" -r /tmp/build/requirements.txt \
 && pip uninstall -y uv && rm -rf /tmp/build

COPY ai/src ${LAMBDA_TASK_ROOT}/ai/src
COPY ai/config ${LAMBDA_TASK_ROOT}/ai/config
COPY prompts ${LAMBDA_TASK_ROOT}/prompts
COPY kb ${LAMBDA_TASK_ROOT}/kb

ENV PYTHONPATH=${LAMBDA_TASK_ROOT}/ai/src:${LAMBDA_TASK_ROOT} \
    CONFIG_DIR=${LAMBDA_TASK_ROOT}/ai/config \
    HOME=/tmp

CMD ["askfirst.handler.lambda_handler"]
