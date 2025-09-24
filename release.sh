#!/bin/bash

while getopts "v:p:r:m:d:i:" opt; do
  case "$opt" in
    v) version="$OPTARG" ;;
    p) gcp_project_id="$OPTARG" ;;
    r) gcp_region="$OPTARG" ;;
    m) maven_repository="$OPTARG" ;;
    d) docker_repository="$OPTARG" ;;
    \?) echo "Invalid option: -$OPTARG" >&2; exit 1 ;;
    :) echo "Option -$OPTARG requires an argument." >&2; exit 1 ;;
  esac
done

command="true"
command="$command && gcloud builds submit . "
command="$command --async "
command="$command --region=\$_GCP_REGION "
command="$command --machine-type=e2-medium "
command="$command --config=./cloudbuild.yaml "
command="$command --substitutions=_VERSION=$version"
command="$command,_GCP_PROJECT_ID=$gcp_project_id"
command="$command,_GCP_REGION=$gcp_region"
command="$command,_MAVEN_REPOSITORY=$maven_repository"
command="$command,_DOCKER_REPOSITORY=$docker_repository"
command="$command "

echo "Executing command: $command"
eval $command
