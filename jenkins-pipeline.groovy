#!groovy

podTemplate(
    inheritFrom: "maven",
    label: "myJenkinsMavenSlave",
    cloud: "openshift",
    volumes: [
        persistentVolumeClaim(claimName: "m2repo", mountPath: "/tmp/.m2")
    ]) 
{
node('myJenkinsMavenSlave') {

    def devProject = 'tasks-dev';
    def prodProject = 'tasks-prod';

    // Make sure your nexus_openshift_settings.xml
    // Is pointing to your nexus instance
    def mvnCmd = "mvn -s ./nexus_openshift_settings.xml"

    stage('Checkout Source') {
        checkout scm
    }

    // The following variables need to be defined at the top level and not inside
    // the scope of a stage - otherwise they would not be accessible from other stages.
    // Extract version and other properties from the pom.xml
    def groupId    = getGroupIdFromPom("pom.xml")
    def artifactId = getArtifactIdFromPom("pom.xml")
    def version    = getVersionFromPom("pom.xml")

    stage('Build war') {
        echo "Building version ${version}"

        sh "${mvnCmd} clean package -DskipTests"
    }

    stage('Unit Tests') {
        echo "Unit Tests"
        sh "${mvnCmd} test"
    }

    stage('Code Analysis') {
        echo "Code Analysis"

        // Replace xyz-sonarqube with the name of your project
        sh "${mvnCmd} sonar:sonar -Dsonar.host.url=http://sonarqube.myproject.svc.cluster.local:9000/ -Dsonar.projectName=${JOB_BASE_NAME}"
    }

    stage('Publish to Nexus') {
        echo "Publish to Nexus"

        // Replace xyz-nexus with the name of your project
        sh "${mvnCmd} deploy -DskipTests=true -DaltDeploymentRepository=nexus::default::http://nexus3.myproject.svc.cluster.local:8081/repository/releases"
    }

    stage('Build OpenShift Image') {
        def newTag = "TestingCandidate-${version}"
        echo "New Tag: ${newTag}"

        // Copy the war file we just built and rename to ROOT.war
        sh "cp ./target/openshift-tasks.war ./ROOT.war"

        // Start Binary Build in OpenShift using the file we just published
        // Replace xyz-tasks-dev with the name of your dev project
        sh "oc project ${devProject}"
        sh "oc start-build tasks --follow --from-file=./ROOT.war -n ${devProject}"

        openshiftTag alias: 'false', destStream: 'tasks', destTag: newTag, destinationNamespace: '${devProject}', namespace: '${devProject}', srcStream: 'tasks', srcTag: 'latest', verbose: 'false'
    }

    stage('Deploy to Dev') {
        // Patch the DeploymentConfig so that it points to the latest TestingCandidate-${version} Image.
        // Replace xyz-tasks-dev with the name of your dev project
        sh "oc project ${devProject}"
        sh "oc patch dc tasks --patch '{\"spec\": { \"triggers\": [ { \"type\": \"ImageChange\", \"imageChangeParams\": { \"containerNames\": [ \"tasks\" ], \"from\": { \"kind\": \"ImageStreamTag\", \"namespace\": \"${devProject}\", \"name\": \"tasks:$tagPrefix-$version\"}}}]}}' -n ${devProject}"

        openshiftDeploy depCfg: 'tasks', namespace: '${devProject}', verbose: 'false', waitTime: '', waitUnit: 'sec'
        openshiftVerifyDeployment depCfg: 'tasks', namespace: '${devProject}', replicaCount: '1', verbose: 'false', verifyReplicaCount: 'false', waitTime: '', waitUnit: 'sec'
        openshiftVerifyService namespace: '${devProject}', svcName: 'tasks', verbose: 'false'
    }

    stage('Integration Test') {
        // TBD: Proper test
        // Could use the OpenShift-Tasks REST APIs to make sure it is working as expected.

        def newTag = "prod-ready-${version}"
        echo "New tag: ${newTag}"

        // Replace xyz-tasks-dev with the name of your dev project
        openshiftTag alias: 'false', destStream: 'tasks', destTag: newTag, destinationNamespace: '${devProject}', namespace: '${devProject}', srcStream: 'tasks', srcTag: 'latest', verbose: 'false'
    }

    // Blue/Green Deployment into Production
    // -------------------------------------
    def dest   = "tasks-green"
    def active = ""

    stage('Prep Production Deployment') {
        // Replace xyz-tasks-dev and xyz-tasks-prod with
        // your project names
        sh "oc project ${devProject}"
        sh "oc get route tasks -n ${devProject} -o jsonpath='{ .spec.to.name }' > activesvc.txt"
        active = readFile('activesvc.txt').trim()
        if (active == "tasks-green") {
            dest = "tasks-blue"
        }
        echo "Active svc: " + active
        echo "Dest svc:   " + dest
    }

    stage('Deploy new Version') {
        echo "Deploying to ${dest}"

        // Patch the DeploymentConfig so that it points to
        // the latest ProdReady-${version} Image.
        // Replace xyz-tasks-dev and xyz-tasks-prod with
        // your project names.
        sh "oc patch dc ${dest} --patch '{\"spec\": { \"triggers\": [ { \"type\": \"ImageChange\", \"imageChangeParams\": { \"containerNames\": [ \"$dest\" ], \"from\": { \"kind\": \"ImageStreamTag\", \"namespace\": \"${devProject}\", \"name\": \"tasks:prod-ready-$version\"}}}]}}' -n ${prodProject}"

        openshiftDeploy depCfg: dest, namespace: '${prodProject}', verbose: 'false', waitTime: '', waitUnit: 'sec'
        openshiftVerifyDeployment depCfg: dest, namespace: '${prodProject}', replicaCount: '1', verbose: 'false', verifyReplicaCount: 'true', waitTime: '', waitUnit: 'sec'
        openshiftVerifyService namespace: '${prodProject}', svcName: dest, verbose: 'false'
    }

    stage('Switch over to new Version') {
        input "Switch Production?"

        // Replace xyz-tasks-prod with the name of your
        // production project
        sh 'oc patch route tasks -n ${prodProject} -p \'{"spec":{"to":{"name":"' + dest + '"}}}\''
        sh 'oc get route tasks -n ${prodProject} > oc_out.txt'
        oc_out = readFile('oc_out.txt')
        echo "Current route configuration: " + oc_out
    }
}

// Convenience Functions to read variables from the pom.xml
def getVersionFromPom(pom) {
    def matcher = readFile(pom) =~ '<version>(.+)</version>'
    matcher ? matcher[0][1] : null
}

def getGroupIdFromPom(pom) {
    def matcher = readFile(pom) =~ '<groupId>(.+)</groupId>'
    matcher ? matcher[0][1] : null
}

def getArtifactIdFromPom(pom) {
    def matcher = readFile(pom) =~ '<artifactId>(.+)</artifactId>'
    matcher ? matcher[0][1] : null
}