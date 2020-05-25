// vars/dbmaestro.groovy
import groovy.json.*
import java.io.*
import java.nio.file.*

@groovy.transform.Field
def automation_jar_path = "C:\\apps\\DBmaestro\\TeamWork\\TeamWorkOracleServer\\Automation\\DBmaestroAgent.jar"

@groovy.transform.Field
def project_name = "HR_BYTASK"

@groovy.transform.Field
def rs_environment_name = "\"Release Source\""

@groovy.transform.Field
def auth_type = "DBmaestroAccount"

@groovy.transform.Field
def user_name = "peretzr@dbmaestro.com"

@groovy.transform.Field
def auth_token = "vVk9JtrvhhhVNHxXraTthTEtknQHCjTF"

@groovy.transform.Field
def dbm_address = "localhost"

@groovy.transform.Field
def autopackage_dir = "C:\\apps\\DBmaestro\\Scripts\\TRAIN\\HR_BYTASK\\AUTO_PACKAGE"

@groovy.transform.Field
def rs_schema_name = "HR_BYTASK_RS"

def prepPackageFromGitCommit() {
	def scriptsForPackage = []
	bat "git diff --name-only HEAD~1..HEAD Database\\*.sql > package.files"
	packageFiles = new File("${env.WORKSPACE}\\package.files")
	if (packageFiles.exists()) {
		def fileList = packageFiles.collect {it}
		if (fileList.size() > 0) {
			for (filePath in fileList) {								
				fileDate = new Date(new File("${env.WORKSPACE}\\${filePath}").lastModified())
				scriptsForPackage.add([ filePath: filePath, modified: fileDate ])
			}
		}
	}
	
	if (scriptsForPackage.size() > 0) {
		def version = "V.gitcommit.${env.BUILD_NUMBER}"
		def version_dir = "${autopackage_dir}\\${version}"
		def target_dir = "${version_dir}\\${rs_schema_name}"
		new File(target_dir).mkdirs()

		def scripts = []
		for (item in scriptsForPackage) {
			scriptFileName = item.filePath.substring(item.filePath.lastIndexOf("/") + 1)
			echo scriptFileName
			scripts.add([name: scriptFileName])
			Files.copy(Paths.get("${env.WORKSPACE}\\${item.filePath}"), Paths.get("${target_dir}\\${scriptFileName}"))
		}
		def manifest = new JsonBuilder()
		manifest operation: "create", type: "regular", enabled: true, closed: false, tags: [], scripts: scripts
		new File("${version_dir}\\package.json").write(manifest.toPrettyString())
	}
}

def createPackage() {
	bat "java -jar \"${automation_jar_path}\" -Package -ProjectName ${project_name} -IgnoreScriptWarnings y -AuthType ${auth_type} -Server ${dbm_address} -UserName ${user_name} -Password ${auth_token}"
}

def upgradeReleaseSource() {
	bat "java -jar ${automation_jar_path} -Upgrade -ProjectName ${project_name} -EnvName ${rs_environment_name} -PackageName V.gitcommit.${env.BUILD_NUMBER} -Server ${dbm_address} -AuthType ${auth_type} -UserName ${user_name} -Password ${auth_token}"
}