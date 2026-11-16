FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && useradd --system --uid 10001 --create-home controller
COPY --chown=controller:controller app.py /app/app.py
USER 10001:10001

EXPOSE 8000
CMD ["sh", "-c", "test -n \"$DATABASE_URL\" && test -n \"$FIREBASE_PROJECT_ID\" && exec uvicorn app:app --host 0.0.0.0 --port 8000"]
