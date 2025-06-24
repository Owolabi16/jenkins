def call(Map config = [:]) {
    pipeline {
        environment {
            ENV = config.env ?: "staging"
            PROJECT = config.project ?: "occasio-hosting"
            NAME = "${env.JOB_NAME.split('/')[0].replaceAll(/_\w+$/, '')}"
            APP_NAME = "drupal-${NAME.replaceFirst(/_tg$/, '')}"
            NAMESPACE = "${NAME}-tg"
            AWS_ID = config.awsId ?: "000000000000"
            ECR_REGISTRY = "${AWS_ID}.dkr.ecr.region-code.amazonaws.com"
            REGION = config.region ?: "region-code"

            IMAGE = "${ECR_REGISTRY}/${APP_NAME}"
            IMAGE_SIMPLE_TAG = "${IMAGE}:${env.BRANCH_NAME.replace("/","-")}"
            IMAGE_TAG = "${IMAGE_SIMPLE_TAG}.${env.BUILD_NUMBER}"
            LATEST_TAG = "${IMAGE}:latest"
        }

        agent {
            kubernetes {
                label "web-${NAME}"
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
                    container('aws') {
                        script {
                            buildAndPushImage()
                        }
                    }
                }
            }

            stage("Update Tags") {
                steps {
                    script {
                        updateImageTags()
                    }
                }
            }

            stage('Deploy') {
                steps {
                    container('aws') {
                        script {
                            deployKubernetesFiles()
                        }
                    }
                }
            }
        }
    }
}

// ──────────────── FUNCTIONS ────────────────

def buildAndPushImage() {
    sh """
        aws ecr get-login-password --region ${REGION} | docker login --username AWS --password-stdin ${ECR_REGISTRY}
        docker build -t \${IMAGE_TAG} .
        docker tag \${IMAGE_TAG} \${IMAGE_SIMPLE_TAG}
        docker tag \${IMAGE_TAG} \${LATEST_TAG}
        docker push \${IMAGE_TAG}
        docker push \${IMAGE_SIMPLE_TAG}
        docker push \${LATEST_TAG}
    """
}

def updateImageTags() {
    sh "find ./k8s/${ENV} -name '*.yaml' -exec sed -i.bak 's#\${IMAGE}:latest#\${IMAGE_TAG}#g' {} \\;"
}

def deployKubernetesFiles() {
    // Find all directories with YAML files under k8s/${ENV}
    def directories = sh(
        script: "find ./k8s/${ENV} -name '*.yaml' | xargs dirname | sort -u",
        returnStdout: true
    ).trim().split('\n')
    
    directories.each { dir ->
        if (dir && dir.trim()) {
            echo "Processing directory: ${dir}"
            
            // Apply all YAML files in this directory (excluding .bak files)
            sh "find ${dir} -maxdepth 1 -name '*.yaml' | grep -v '.bak\$' | xargs kubectl apply -f"
        }
    }
    
    // Wait for deployment rollout
    sh "kubectl rollout status deployment/${NAME} -n ${NAMESPACE}"
}
