FROM python:3.12-slim
WORKDIR /app
COPY gateway.py /app/gateway.py
ENV KUN_BIND=0.0.0.0 KUN_USERS_FILE=/config/users.json
USER 65532:65532
EXPOSE 8080
CMD ["python", "gateway.py"]
