// vars/dbmaestro.groovy
import groovy.json.*
import java.io.*
import java.nio.file.*

@groovy.transform.Field
def parameters = [jarPath: "", projectName: "", rsEnvName: "", authType: "", userName: "", authToken: "", server: "", packageDir: "", rsSchemaName: "", packagePrefix: ""]

def prepPackageFromGitCommit() {
	def scriptsForPackage = []
	//bat "git diff --name-only HEAD~1..HEAD Database\\*.sql > package.files"
	stdoutLines = bat([returnStdout: true, script: "git diff --name-only HEAD~1..HEAD Database\\*.sql"]).trim().split("\n");
	/*
	  print("stdout starts here")
	  def i = 0
	  for (line in stdout) {
		  if (i > 0) {
			  print("an interesting line")
			  print(line)
			  i++
		  } else {
			  print("an uninteresting line")
			  i++
		  }
	  }
	  print("stdout ends here")
	*/
	
	//if (stdoutLines.size() < 2) return
	
	fileList = stdoutLines.subList(1, stdoutLines.size()).collect {it}
	if (fileList.size() < 1) return
	for (filePath in fileList) {								
		fileDate = new Date(new File("${env.WORKSPACE}\\${filePath}").lastModified())
		scriptsForPackage.add([ filePath: filePath, modified: fileDate ])
	}
	//for (line in stdoutLines.subList(1, stdoutLines.size())) {
	//}
	
	/*
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
	*/
	
	if (scriptsForPackage.size() < 1) return
	def version = "${parameters.packagePrefix}${env.BUILD_NUMBER}"
	def version_dir = "${parameters.packageDir}\\${version}"
	def target_dir = "${version_dir}\\${parameters.rsSchemaName}"
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

def createPackage() {
	bat "java -jar \"${parameters.jarPath}\" -Package -ProjectName ${parameters.projectName} -IgnoreScriptWarnings y -AuthType ${parameters.authType} -Server ${parameters.server} -UserName ${parameters.userName} -Password ${parameters.authToken}"
}

def upgradeReleaseSource() {
	bat "java -jar \"${parameters.jarPath}\" -Upgrade -ProjectName ${parameters.projectName} -EnvName ${parameters.rsEnvName} -PackageName ${parameters.packagePrefix}${env.BUILD_NUMBER} -Server ${parameters.server} -AuthType ${parameters.authType} -UserName ${parameters.userName} -Password ${parameters.authToken}"
}