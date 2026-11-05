# Apache Tika server with Tesseract OCR, plus the language packs the upstream image leaves out.
FROM apache/tika:4.2.0-full
USER root
RUN apt-get update && apt-get install -y --no-install-recommends tesseract-ocr-ron && rm -rf /var/lib/apt/lists/*
COPY tika-config.json /opt/tika-config.json
ENV NEXORA_OCR_LANGUAGES=eng
USER 35002
