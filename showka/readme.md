# how to make truststore (to test at local)

keytool -import -file certificate.pem -alias local_ap -keystore truststore.jks

# how to build

mvn clean install [-Dmaven.test.skip=true]

# setup oxalis-standalone for as4

Copy AS4 project's built jars to the oxalis-standalone/ext directory.

# how to execute sending

cd /oxalis-dist/oxalis-standalone/target/oxalis-standalone-5.0.3-full/oxalis-standalone

java -classpath lib/*:ext/* eu.sendregning.oxalis.Main -f ~/.oxalis/files/peppol-bis-invoice-sbdh.xml -u http://localhost:8080/oxalis/as4 -cert ~/.oxalis/key/certificate.pem --protocol peppol-transport-as4-v2_0
