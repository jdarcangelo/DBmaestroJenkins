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
	def outList = stdoutLines.trim().split("\n").collect {it.replace("/", "\\")}
	return outList[1..-1]
}

def findActionableFiles(String commit) {
	echo "Finding actionable file changes in ${commit}"
	return execCommand("git diff --name-only --diff-filter=AM ${commit}~1..${commit} Database\\*.sql")
	/*
	def actionableFiles = []
	if (fileList.size() < 1) return actionableFiles
	
	for (changedFile in fileList) {
		def changeType = changedFile.split('\t')[0]
		def filePath = changedFile.split('\t')[1]
		switch (changeType) {
			case 'D':
				echo "${filePath} was deleted, skipping..."
				continue
			case 'M':
				echo "${filePath} was modified, adding to package..."
				actionableFiles.add(filePath)
				break
			case 'A':
				echo "${filePath} was added, adding to package..."
				actionableFiles.add(filePath)
				break
		}
	}
	
	return actionableFiles
	*/
}

@NonCPS
def sortScriptsForPackage(List<Map> scriptsForPackage) {
	return scriptsForPackage.toSorted { a, b -> a.modified.compareTo(b.modified) }
}

@NonCPS
def createPackageManifest(List<String> scripts, String target) {
	def manifest = new JsonBuilder()
	manifest operation: "create", type: "regular", enabled: true, closed: false, tags: [], scripts: scripts
	echo "Generating manifest:"
	def manifestOutput = manifest.toPrettyString()
	echo manifestOutput
	File manifestFile = new File("${env.WORKSPACE}\\package.json")
	manifestFile.setWritable(true)
	manifestFile.write(manifestOutput)
	bat "move \"${env.WORKSPACE}\\package.json\" \"${target}\""
}

//@NonCPS
def prepPackageFromGitCommit() {
	def scriptsForPackage = []

	echo "gathering sql files from Database directory modified or created in the latest commit"
	def fileList = findActionableFiles("HEAD")
	//def fileList = execCommand("git diff --name-status HEAD~1..HEAD Database\\*.sql")
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
	
	if (parents.size() > 1) {
		def cherryCmd = "git cherry -v ${parents[0]} ${parents[1]}"
		echo "Commit is result of merge; finding branch history with git cherry command: ${cherryCmd}"
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
			def changedFiles = findActionableFiles(commitHash)
			//def changedFiles = execCommand("git diff --name-only ${commitHash}~1..${commitHash} Database\\*.sql")
			for (changedFile in changedFiles) {
				scriptForPackage = scriptsForPackage.find {it.filePath == changedFile}
				scriptForPackage.modified = commitDate
				scriptForPackage.commit = [commitType: commitType, commitHash: commitHash, commitMail: commitMail] // commitDesc: commitDesc, 
				echo "File (${scriptForPackage.filePath}) updated in ${scriptForPackage.commit.commitHash} on ${scriptForPackage.modified} by ${scriptForPackage.commit.commitMail}"
			}
		}
	} else {
		echo "Direct commit found; acquiring commit details"
		def commitType = "+"
		def commitHash = execCommand("git show --pretty=%%H")[0]
		def commitDate = new Date(execCommand("git show --pretty=%%cd")[0])
		def commitMail = execCommand("git show --pretty=%%ce")[0]

		echo "Finding files associated with commit ${commitHash}"
		def changedFiles = findActionableFiles("HEAD")
		//def changedFiles = execCommand("git diff --name-only HEAD~1..HEAD Database\\*.sql")
		for (changedFile in changedFiles) {
			scriptForPackage = scriptsForPackage.find {it.filePath == changedFile}
			scriptForPackage.modified = commitDate
			scriptForPackage.commit = [commitType: commitType, commitHash: commitHash, commitMail: commitMail] // commitDesc: commitDesc, 
			echo "File (${scriptForPackage.filePath}) updated in ${scriptForPackage.commit.commitHash} on ${scriptForPackage.modified} by ${scriptForPackage.commit.commitMail}"
		}
	}	
	
	def version = "${parameters.packagePrefix}${env.BUILD_NUMBER}"
	echo "Preparing package ${version}"
	def version_dir = "${parameters.packageDir}\\${version}"
	def target_dir = "${version_dir}\\${parameters.rsSchemaName}"
	// new File(target_dir).mkdirs()

	def scripts = []
	scriptsForPackage = sortScriptsForPackage(scriptsForPackage)
	for (item in scriptsForPackage) {
		def scriptFileName = item.filePath.substring(item.filePath.lastIndexOf("\\") + 1)
		// , tags: [[tagNames: [item.commit.commitMail, item.commit.commitHash], tagType: "Custom"]]
		scripts.add([name: scriptFileName])
		echo "Added ${item.filePath} to package staging and manifest"
		
		bat "mkdir \"${target_dir}\""
		bat "copy /Y \"${env.WORKSPACE}\\${item.filePath}\" \"${target_dir}\""
		
		/*
		def sourceFile = Paths.get("${env.WORKSPACE}\\${item.filePath}")
		def targetDir = Paths.get(target_dir)
		def targetFile = targetDir.resolve(sourceFile.getFileName())
		
		Files.copy(sourceFile, targetFile)
		*/
	}
	createPackageManifest(scripts, version_dir)
}

def createPackage() {
	bat "java -jar \"${parameters.jarPath}\" -Package -ProjectName ${parameters.projectName} -IgnoreScriptWarnings y -AuthType ${parameters.authType} -Server ${parameters.server} -UserName ${parameters.userName} -Password ${parameters.authToken}"
}

def upgradeReleaseSource() {
	bat "java -jar \"${parameters.jarPath}\" -Upgrade -ProjectName ${parameters.projectName} -EnvName ${parameters.rsEnvName} -PackageName ${parameters.packagePrefix}${env.BUILD_NUMBER} -Server ${parameters.server} -AuthType ${parameters.authType} -UserName ${parameters.userName} -Password ${parameters.authToken}"
}
