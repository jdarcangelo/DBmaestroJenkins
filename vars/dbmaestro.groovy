// vars/dbmaestro.groovy
import groovy.json.*
import java.io.*
import java.nio.file.*

@groovy.transform.Field
def parameters = [jarPath: "", projectName: "", rsEnvName: "", authType: "", userName: "", authToken: "", server: "", packageDir: "", rsSchemaName: "", packagePrefix: ""]

// Capture stdout lines, strip first line echo of provided command
def execCommand(String script) {
	echo "Executing git command: ${script}"
	def stdoutLines = bat([returnStdout: true, script: script])
	echo stdoutLines
	def outList = stdoutLines.trim().split("\n").collect {it}
	return outList[1..-1]
}

@NonCPS
def sortScriptsForPackage(List<Map> scriptsForPackage) {
	return scriptsForPackage.toSorted { a, b -> a.modified.compareTo(b.modified) }
}

//@NonCPS
def prepPackageFromGitCommit() {
	def scriptsForPackage = []

	echo "gathering sql files from Database directory modified or created in the latest commit"
	def fileList = execCommand("git diff --name-only HEAD~1..HEAD Database\\*.sql")
	if (fileList.size() < 1) return
	echo "found " + fileList.size() + " sql files"
	for (filePath in fileList) {
		fileDate = new Date(new File("${env.WORKSPACE}\\${filePath}").lastModified())
		echo "File (${filePath}) found, last modified ${fileDate}"
		scriptsForPackage.add([ filePath: filePath, modified: fileDate, commit: [:] ])
	}
	
	if (scriptsForPackage.size() < 1) return
	
	// 
	echo "Getting parents of the current HEAD"
	def parentList = execCommand("git log --pretty=%%P -n 1")
	if (parentList.size() < 1) return

	echo "Parent git hash(es) found: ${parentList}"
	def parents = parentList[0].split(" ")
	def cherryCmd = "git cherry -v ${parents[0]} "
	if (parents.size() > 1) {
		cherryCmd = cherryCmd + parents[1]
	}

	echo "Finding branch history with git cherry command: ${cherryCmd}"
	def commitLines = execCommand(cherryCmd)
	commitLines.each { line -> echo(line) }
	for (commitLine in commitLines) {
		def details = commitLine.split(" ")
		def commitType = details[0]
		def commitHash = details[1]
		// def commitDesc = details[2..-1].join(" ")
		def commitDate = new Date(execCommand("git show --pretty=%%cd ${commitHash}")[0])
		def commitMail = execCommand("git show --pretty=%%ce ${commitHash}")[0]
		echo "Ancestor commit found: ${commitType} ${commitDate} ${commitHash} ${commitMail}" // ${commitDesc}
		
		echo "Finding files associated with commit ${commitHash}"
		def changedFiles = execCommand("git diff --name-only ${commitHash} Database\\*.sql")
		for (changedFile in changedFiles) {
			scriptForPackage = scriptsForPackage.find {it.filePath == changedFile}
			scriptForPackage.modified = commitDate
			scriptForPackage.commit = [commitType: commitType, commitHash: commitHash, commitMail: commitMail] // commitDesc: commitDesc, 
			echo "File (${scriptForPackage.filePath}) updated in ${scriptForPackage.commit.commitHash} on ${scriptForPackage.modified} by ${scriptForPackage.commit.commitMail}"
		}
	}
	
	echo "Preparing package ${version}"
	def version = "${parameters.packagePrefix}${env.BUILD_NUMBER}"
	def version_dir = "${parameters.packageDir}\\${version}"
	def target_dir = "${version_dir}\\${parameters.rsSchemaName}"
	new File(target_dir).mkdirs()

	def scripts = []
	scriptsForPackage = sortScriptsForPackage(scriptsForPackage)
	for (item in scriptsForPackage) {
		def scriptFileName = item.filePath.substring(item.filePath.lastIndexOf("/") + 1)
		// , tags: [[tagNames: [item.commit.commitMail, item.commit.commitHash], tagType: "Custom"]]
		scripts.add([name: scriptFileName])
		echo "Added ${item.filePath} to package staging and manifest"
		Files.copy(Paths.get("${env.WORKSPACE}\\${item.filePath}"), Paths.get("${target_dir}\\${scriptFileName}"))
	}
	def manifest = new JsonBuilder()
	manifest operation: "create", type: "regular", enabled: true, closed: false, tags: [], scripts: scripts
	echo "Generating manifest:"
	def manifestOutput = manifest.toPrettyString()
	echo manifestOutput
	new File("${version_dir}\\package.json").write(manifestOutput)
}

def createPackage() {
	bat "java -jar \"${parameters.jarPath}\" -Package -ProjectName ${parameters.projectName} -IgnoreScriptWarnings y -AuthType ${parameters.authType} -Server ${parameters.server} -UserName ${parameters.userName} -Password ${parameters.authToken}"
}

def upgradeReleaseSource() {
	bat "java -jar \"${parameters.jarPath}\" -Upgrade -ProjectName ${parameters.projectName} -EnvName ${parameters.rsEnvName} -PackageName ${parameters.packagePrefix}${env.BUILD_NUMBER} -Server ${parameters.server} -AuthType ${parameters.authType} -UserName ${parameters.userName} -Password ${parameters.authToken}"
}