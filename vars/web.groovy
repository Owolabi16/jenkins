def call(Map config = [:]) {
  pipeline {
    environment {
      ENV = "staging"
      PROJECT = "occasio-hosting"
      APP_NAME = "drupal-${NAME.replaceFirst(/_tg$/, '')}"
      NAMESPACE = "${NAME}-tg"
      ECR_REGISTRY = "${AWS_ID}.dkr.ecr.region-code.amazonaws.com"
      REGION = "us-east-1"

      IMAGE = "${ECR_REGISTRY}/${APP_NAME}"
      IMAGE_SIMPLE_TAG = "${IMAGE}:${env.BRANCH_NAME.replace("/","-")}"
      IMAGE_TAG = "${IMAGE_SIMPLE_TAG}.${env.BUILD_NUMBER}"
    }


  agent {
    kubernetes {
      label 'web-talentportal'
      yaml """
apiVersion: v1
kind: Pod
metadata:
  labels:
    component: ci
spec:
  serviceAccountName: jenkins
  containers:
  - name: aws
    image: 'amazon/aws-cli'
    command:
    - cat
    tty: true
"""
    }
  }

  stages {
    stage('Build and Push Image to ECR') {
      steps {
        script {
            container('aws') {
              sh '''
                aws ecr get-login-password --region region-code | docker login --username AWS --password-stdin ${AWS_ID}.dkr.ecr.region-code.amazonaws.com
                docker build -t ${IMAGE_TAG} .
                docker tag ${IMAGE_TAG} ${IMAGE_SIMPLE_TAG}
                docker tag ${IMAGE_TAG} ${LATEST_TAG}
                docker push ${IMAGE_TAG}
                docker push ${IMAGE_SIMPLE_TAG}
                docker push ${LATEST_TAG}
              '''
            }
        }
      }
    }

    stage('Deploy Staging') {
      steps {
        script {
          if (params.deploy) {
            container('aws') {
              sh("sed -i.bak 's#${IMAGE}:latest#${IMAGE_TAG}#' ./k8s/staging/deployment.yaml")
              sh("sed -i.bak 's#${IMAGE}:latest#${IMAGE_TAG}#' ./k8s/staging/cronjob-drush.yaml")

              sh '''#!/bin/bash
                kubectl apply -f ./k8s/staging/configmap.yaml
                kubectl apply -f ./k8s/staging/deployment.yaml
                kubectl apply -f ./k8s/staging/ingress.yaml
                kubectl apply -f ./k8s/staging/cronjobs.yaml
                kubectl apply -f ./k8s/staging/cronjob-drush.yaml

                ATTEMPTS=0
                ROLLOUT_STATUS_CMD="kubectl rollout status deployment/web-talentportal -n web-talent-tg"
                until $ROLLOUT_STATUS_CMD || [ $ATTEMPTS -eq 60 ]; do
                  $ROLLOUT_STATUS_CMD
                  ATTEMPTS=$((ATTEMPTS + 1))
                  sleep 10
                done
                if [[ $ATTEMPTS -eq 60 ]]; then exit 1; fi
              '''

              sh '''
                sleep 20
                DRUPAL_POD=$(kubectl get pods -n web-talent-tg --field-selector=status.phase=Running -o name --no-headers=true -o custom-columns=":metadata.name" | grep web-talentportal- | head -n 1)
                kubectl exec -n web-talent-tg $DRUPAL_POD -- bash -c 'cd /app && bash deploy/post-deploy.sh'
              '''
            }
          }
        }
      }
    }
  }

// ──────────────── FUNCTIONS ────────────────

def validateKubernetesFiles() {
    container("kubeconform") {
        sh "find ./k8s/${ENV} -name '*.yaml' -exec kubeconform -strict -exit-on-error {} \\;"
    }
}

def buildAndPushImage() {
    sh '''
        aws ecr get-login-password --region region-code | docker login --username AWS --password-stdin 000000000000.dkr.ecr.region-code.amazonaws.com
        docker build -t ${IMAGE_TAG} .
        docker tag ${IMAGE_TAG} ${IMAGE_SIMPLE_TAG}
        docker push ${IMAGE_TAG}
        docker push ${IMAGE_SIMPLE_TAG}
    '''
}

def deployApplication() {
    // Find all directories with YAML files under k8s/${ENV}
    def directories = sh(
        script: "find ./k8s/${ENV} -name '*.yaml' | xargs dirname | sort -u",
        returnStdout: true
    ).trim().split('\n')
    
    directories.each { dir ->
        if (dir && dir.trim()) {
            echo "Processing directory: ${dir}"
            
            // Update image tags in this directory
            sh "find ${dir} -maxdepth 1 -name '*.yaml' -exec sed -i.bak 's#${IMAGE}:latest#${IMAGE_TAG}#g' {} \\;"
            
            // Apply all YAML files in this directory
            sh "find ${dir} -maxdepth 1 -name '*.yaml' | grep -v '.bak\ | xargs kubectl apply -f"
        }
    }
    
    // Wait for deployment rollout
    sh "kubectl rollout status deployment/${NAME} -n ${NAMESPACE}"
}
