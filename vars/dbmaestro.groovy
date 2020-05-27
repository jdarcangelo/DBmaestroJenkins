// vars/dbmaestro.groovy
import groovy.json.*
import java.io.*
import java.nio.file.*

@groovy.transform.Field
def parameters = [jarPath: "", projectName: "", rsEnvName: "", authType: "", userName: "", authToken: "", server: "", packageDir: "", rsSchemaName: "", packagePrefix: ""]

def prepPackageFromGitCommit() {
	scriptsForPackage = []

	// Find all the changed sql files since previous commit
	stdoutLines = bat([returnStdout: true, script: "git diff --name-only HEAD~1..HEAD Database\\*.sql"]).trim().split("\n")
	// There will be one line per sql file relative to the working dir
	fileList = stdoutLines.collect {it}
	if (fileList.size() < 2) return
	for (filePath in fileList) {
		// Ignore the first line echo of the git command
		if (filePath == fileList.first()) continue
		fileDate = new Date(new File("${env.WORKSPACE}\\${filePath}").lastModified())
		scriptsForPackage.add([ filePath: filePath, modified: fileDate, commit: [:] ])
	}
	
	if (scriptsForPackage.size() < 1) return
	
	// Get the parents of the current HEAD, if two parents, we want to walk all the commits of the merge
	stdoutLines = bat([returnStdout: true, script: "git log --pretty=%%P -n 1"]).trim().split("\n")
	parentList = stdoutLines.collect {it}
	if (parentList.size() < 2) return
	
	parents = parentList[1].split(" ")
	cherryCmd = "git cherry -v ${parents[0]} "
	if (parents.size() > 1) {
		cherryCmd = cherryCmd + parents[1]
	}
	
	stdoutLines = bat([returnStdout: true, script: cherryCmd]).trim().split("\n")
	commitLines = stdoutLines.collect {it}
	for (commitLine in commitLines) {
		// Ignore the first line echo of the git command
		if (commitLine == commitLines.first()) continue
		details = commitLine.split(" ")
		commitType = details[0]
		commitHash = details[1]
		
		// Get the date of the commit
		stdoutLines = bat([returnStdout: true, script: "git show --pretty=%%cd ${commitHash}"]).trim().split("\n")
		commitDate = new Date(stdoutLines[1])
		
		// Get the committer
		stdoutLines = bat([returnStdout: true, script: "git show --pretty=%%ce ${commitHash}"]).trim().split("\n")
		commitMail = new Date(stdoutLines[1])
		
		// Get sql files changed in the commit
		stdoutLines = bat([returnStdout: true, script: "git diff --name-only ${commitHash} Database\\*.sql"]).trim().split("\n")
		for (changedFile in stdoutLines) {
			if (changedFile == stdoutLines.first()) continue
			scriptForPackage = scriptsForPackage.find {it.filePath == changedFile}
			scriptForPackage.modified = commitDate
			scriptForPackage.commit = [commitType: commitType, commitHash: commitHash, commitMail: commitMail]
		}
	}
	
	version = "${parameters.packagePrefix}${env.BUILD_NUMBER}"
	version_dir = "${parameters.packageDir}\\${version}"
	target_dir = "${version_dir}\\${parameters.rsSchemaName}"
	new File(target_dir).mkdirs()

	scripts = []
	for (item in scriptsForPackage.sort {it.modified} ) {
		scriptFileName = item.filePath.substring(item.filePath.lastIndexOf("/") + 1)
		scripts.add([name: scriptFileName, tags: [[tagNames: [item.commit.commitMail, item.commit.commitHash], tagType: "Custom"]]])
		Files.copy(Paths.get("${env.WORKSPACE}\\${item.filePath}"), Paths.get("${target_dir}\\${scriptFileName}"))
	}
	manifest = new JsonBuilder()
	manifest operation: "create", type: "regular", enabled: true, closed: false, tags: [], scripts: scripts
	new File("${version_dir}\\package.json").write(manifest.toPrettyString())
}

def createPackage() {
	bat "java -jar \"${parameters.jarPath}\" -Package -ProjectName ${parameters.projectName} -IgnoreScriptWarnings y -AuthType ${parameters.authType} -Server ${parameters.server} -UserName ${parameters.userName} -Password ${parameters.authToken}"
}

def upgradeReleaseSource() {
	bat "java -jar \"${parameters.jarPath}\" -Upgrade -ProjectName ${parameters.projectName} -EnvName ${parameters.rsEnvName} -PackageName ${parameters.packagePrefix}${env.BUILD_NUMBER} -Server ${parameters.server} -AuthType ${parameters.authType} -UserName ${parameters.userName} -Password ${parameters.authToken}"
}