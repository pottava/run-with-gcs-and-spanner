# Eventarc + GCS で試す Cloud Run (Java)

GCS にアップロードした CSV をパースし、Spanner へデータを保存します。

## 開発環境セットアップ

1. 環境変数をセットし、Cloud SDK にプロジェクト管理者権限でログインします

  ```sh
  APP_NAME=csvs-to-spanner
  BUCKET_NAME=csvs-to-spanner
  GCLOUD_REGION=asia-northeast1
  gcloud auth login
  ```

2. GCS にバケットを作り、サンプルのファイルをアップします

  ```sh
  gsutil mb -l "${GCLOUD_REGION}" "gs://${BUCKET_NAME}"
  cat << EOF >test.csv
  value
  100
  200
  EOF
  gsutil cp test.csv "gs://${BUCKET_NAME}/test.csv"
  ```

3. Cloud Spanner にインスタンス・テーブルを用意します

  ```sh
  gcloud beta spanner instances create "${APP_NAME}-db" \
    --description "rdb for sample app" \
    --config "regional-${GCLOUD_REGION}" \
    --processing-units 100
  gcloud spanner databases create app --instance "${APP_NAME}-db" \
    --ddl='CREATE TABLE app (id INT64, value INT64) PRIMARY KEY(id)'
  ```

3. GCS のサービス アカウントに必要な権限を付与します

  ```sh
  PROJECT_ID="$( gcloud config get-value project )"
  SA="$( gsutil kms serviceaccount )"
  gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
    --member="serviceAccount:${SA}" --role='roles/storage.objectViewer'
  gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
    --member="serviceAccount:${SA}" --role='roles/pubsub.publisher'
  ```

4. ローカルで Cloud Run アプリケーションを起動、開発します  
  （初回起動には 5 分くらいかかります）

  ```sh
  gcloud beta code dev --application-default-credential
  ```

5. ローカルで挙動をテストします

  ```sh
  curl -s https://googleapis.github.io/google-cloudevents/testdata/google/events/cloud/storage/v1/StorageObjectData-complex.json \
    | sed -e "s|sample-bucket|${BUCKET_NAME}|" \
    | sed -e "s|MyFile|test.csv| " > sample.json
  curl -iXPOST -H 'Content-type: application/json' http://localhost:8080/ \
    -H "ce-id: id" -H "ce-source: src" -H "ce-type: type" \
    -H "ce-specversion: spec" -H "ce-subject: ${BUCKET_NAME}" \
    -d @sample.json
  ```

6. Cloud Run をデプロイします

  ```sh
  gcloud run deploy "${APP_NAME}" --region "${GCLOUD_REGION}" \
    --platform managed --no-allow-unauthenticated \
    --source .
  ```

7. Eventarc を設定します  
  （このコマンドで Pub/Sub の必要なリソースも作成されます）

  ```sh
  PROJECT_NUMBER="$( gcloud projects list --filter="${PROJECT_ID}" \
    --format='value(PROJECT_NUMBER)' )"
  gcloud eventarc triggers create "${APP_NAME}-trigger" \
    --event-filters="type=google.cloud.storage.object.v1.finalized" \
    --event-filters="bucket=${BUCKET_NAME}" \
    --destination-run-service "${APP_NAME}" \
    --destination-run-region "${GCLOUD_REGION}" \
    --service-account="${PROJECT_NUMBER}-compute@developer.gserviceaccount.com" \
    --location "${GCLOUD_REGION}"
  gcloud eventarc triggers list --location "${GCLOUD_REGION}"
  ```

8. ログを確認します

  ```sh
  gcloud logging read "resource.type=cloud_run_revision AND \
    resource.labels.service_name=${APP_NAME}" \
    --format 'value(textPayload)' --limit 30
  ```

## CI/CD セットアップ

1. サービスアカウントとその鍵を生成します

  ```sh
  gcloud iam service-accounts create deployer \
    --display-name "Deployer admin account"
  SA_EMAIL="$( gcloud iam service-accounts list \
    --filter 'email ~ deployer@.*'  --format='value(email)' )"
  gcloud projects add-iam-policy-binding "${GCLOUD_PROJECT_ID}" \
    --member "serviceAccount:${SA_EMAIL}" \
    --role roles/editor
  gcloud iam service-accounts keys create deployer-creds.json \
    --iam-account "${SA_EMAIL}"
  ```

2. Circle CI の環境変数 `GOOGLE_CREDENTIALS` に以下の値を設定

  ```sh
  cat deployer-creds.json
  ```
