FROM python:3.12-alpine

WORKDIR /app

COPY app.py ui.html ./
COPY samples ./samples

EXPOSE 8080

CMD ["python", "app.py"]
