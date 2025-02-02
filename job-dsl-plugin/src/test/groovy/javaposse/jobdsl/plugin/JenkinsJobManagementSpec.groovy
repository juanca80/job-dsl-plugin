package javaposse.jobdsl.plugin

import com.cloudbees.hudson.plugins.folder.AbstractFolder
import com.cloudbees.hudson.plugins.folder.AbstractFolderProperty
import com.cloudbees.hudson.plugins.folder.AbstractFolderPropertyDescriptor
import com.cloudbees.hudson.plugins.folder.Folder
import com.cloudbees.hudson.plugins.folder.properties.FolderCredentialsProvider
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.domains.Domain
import com.cloudbees.plugins.credentials.domains.DomainCredentials
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl
import com.google.common.io.Resources
import hudson.Extension
import hudson.FilePath
import hudson.model.AbstractBuild
import hudson.model.AllView
import hudson.model.Cause
import hudson.model.Failure
import hudson.model.FreeStyleBuild
import hudson.model.FreeStyleProject
import hudson.model.Job
import hudson.model.ListView
import hudson.model.Run
import hudson.model.View
import hudson.model.listeners.SaveableListener
import hudson.tasks.ArtifactArchiver
import hudson.tasks.Fingerprinter
import javaposse.jobdsl.dsl.ConfigurationMissingException
import javaposse.jobdsl.dsl.DslException
import javaposse.jobdsl.dsl.DslScriptException
import javaposse.jobdsl.dsl.Item
import javaposse.jobdsl.dsl.JobManagement
import javaposse.jobdsl.dsl.NameNotProvidedException
import javaposse.jobdsl.dsl.UserContent
import javaposse.jobdsl.dsl.helpers.step.StepContext
import javaposse.jobdsl.dsl.helpers.triggers.TriggerContext
import javaposse.jobdsl.dsl.views.ColumnsContext
import javaposse.jobdsl.plugin.fixtures.TestContextExtensionPoint
import javaposse.jobdsl.plugin.fixtures.TestContextExtensionPoint2
import jenkins.model.Jenkins
import org.custommonkey.xmlunit.XMLUnit
import org.junit.Rule
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.WithoutJenkins
import spock.lang.IgnoreIf
import spock.lang.Specification

import static hudson.model.Result.UNSTABLE
import static java.nio.charset.StandardCharsets.UTF_8

class JenkinsJobManagementSpec extends Specification {
    private static final String FILE_NAME = 'test.txt'
    private static final String JOB_NAME = 'test-job'

    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule()

    private ByteArrayOutputStream buffer
    private Job seedJob
    private Run build
    private JenkinsJobManagement jobManagement
    private JenkinsJobManagement testJobManagement

    def setup() {
        seedJob = Mock(Job)
        build = Mock(Run)

        build.parent >> seedJob

        buffer = new ByteArrayOutputStream()
        jobManagement = new JenkinsJobManagement(
                new PrintStream(buffer), [:], build, new FilePath(new File('.')), LookupStrategy.JENKINS_ROOT
        )
        testJobManagement = new JenkinsJobManagement(new PrintStream(buffer), [:], new File('.'))
    }

    @WithoutJenkins
    def 'createOrUpdateView without name'() {
        when:
        jobManagement.createOrUpdateView(null, '<View/>', true)

        then:
        thrown(NameNotProvidedException)

        when:
        jobManagement.createOrUpdateView('', '<View/>', true)

        then:
        thrown(NameNotProvidedException)
    }

    @WithoutJenkins
    def 'createOrUpdateView without config'() {
        when:
        jobManagement.createOrUpdateView('test', null, true)

        then:
        thrown(ConfigurationMissingException)

        when:
        jobManagement.createOrUpdateView('test', null, true)

        then:
        thrown(ConfigurationMissingException)
    }

    @WithoutJenkins
    def 'createOrUpdateView with invalid name'() {
        when:
        jobManagement.createOrUpdateView('t<e*st', '<View/>', true)

        then:
        thrown(Failure)
    }

    def 'logPluginDeprecationWarning for unknown plugin'() {
        when:
        jobManagement.logPluginDeprecationWarning('foo', '1.2.3')

        then:
        buffer.size() == 0
    }

    def 'logPluginDeprecationWarning for plugin'() {
        when:
        jobManagement.logPluginDeprecationWarning('script-security', '20.0')

        then:
        buffer.toString() =~
                /Warning: \(.+, line \d+\) support for Script Security Plugin versions older than 20.0 is deprecated/
    }

    def 'logPluginDeprecationWarning does not log anything if plugin version is newer'() {
        when:
        jobManagement.logPluginDeprecationWarning('script-security', '1.0')

        then:
        buffer.size() == 0
    }

    @SuppressWarnings('UnnecessarySetter') // false positive for setResult
    def 'requirePlugin not installed'() {
        when:
        jobManagement.requirePlugin('foo')

        then:
        1 * build.setResult(UNSTABLE)
        buffer.size() > 0
    }

    def 'requirePlugin not installed (without build)'() {
        when:
        testJobManagement.requirePlugin('foo')

        then:
        buffer.size() > 0
        noExceptionThrown()
    }

    def 'fail requirePlugin not installed'() {
        when:
        jobManagement.requirePlugin('foo', true)

        then:
        thrown(DslScriptException)
    }

    @SuppressWarnings('UnnecessarySetter') // false positive for setResult
    def 'requirePlugin success'() {
        when:
        jobManagement.requirePlugin('script-security', failIfMissing)

        then:
        0 * build.setResult(UNSTABLE)
        buffer.size() == 0
        noExceptionThrown()

        where:
        failIfMissing << [true, false]
    }

    @SuppressWarnings('UnnecessarySetter') // false positive for setResult
    def 'checkMinimumPluginVersion not installed'() {
        when:
        jobManagement.requireMinimumPluginVersion('foo', '1.2.3')

        then:
        1 * build.setResult(UNSTABLE)
        buffer.size() > 0
    }

    @SuppressWarnings('UnnecessarySetter') // false positive for setResult
    def 'checkMinimumPluginVersion too old'() {
        when:
        jobManagement.requireMinimumPluginVersion('ldap', '20.0')

        then:
        1 * build.setResult(UNSTABLE)
        buffer.size() > 0
    }

    def 'fail checkMinimumPluginVersion not installed'() {
        when:
        jobManagement.requireMinimumPluginVersion('foo', '1.2.3', true)

        then:
        thrown(DslScriptException)
    }

    def 'fail checkMinimumPluginVersion too old'() {
        when:
        jobManagement.requireMinimumPluginVersion('ldap', '20.0', true)

        then:
        thrown(DslScriptException)
    }

    @SuppressWarnings('UnnecessarySetter') // false positive for setResult
    def 'checkMinimumPluginVersion success'() {
        when:
        jobManagement.requireMinimumPluginVersion('script-security', '1.1', failIfMissing)

        then:
        0 * build.setResult(UNSTABLE)
        buffer.size() == 0
        noExceptionThrown()

        where:
        failIfMissing << [true, false]
    }

    @SuppressWarnings('UnnecessarySetter') // false positive for setResult
    def 'requireMinimumCoreVersion success'() {
        when:
        jobManagement.requireMinimumCoreVersion('1.480')

        then:
        0 * build.setResult(UNSTABLE)
        buffer.size() == 0
    }

    @SuppressWarnings('UnnecessarySetter') // false positive for setResult
    def 'requireMinimumCoreVersion failed'() {
        when:
        jobManagement.requireMinimumCoreVersion('3.600')

        then:
        1 * build.setResult(UNSTABLE)
        buffer.size() > 0
    }

    def 'callExtension not found'() {
        when:
        Node result = jobManagement.callExtension('foo', Mock(Item), StepContext)

        then:
        result == null
    }

    def 'callExtension with no args'() {
        when:
        Node result = jobManagement.callExtension('test', Mock(Item), StepContext)

        then:
        isXmlIdentical('extension.xml', result)
    }

    def 'callExtension defined twice'() {
        when:
        jobManagement.callExtension('twice', Mock(Item), StepContext)

        then:
        Exception e = thrown(DslException)
        e.message.contains(TestContextExtensionPoint.name)
        e.message.contains(TestContextExtensionPoint2.name)
    }

    def 'callExtension with object result'() {
        when:
        Node result = jobManagement.callExtension('testComplexObject', Mock(Item), StepContext, 'foo', 42, true)

        then:
        isXmlIdentical('extension.xml', result)
    }

    def 'callExtension with closure'() {
        setup:
        Closure closure = {
            value1('foo')
            value2(42)
            value3(true)
        }

        when:
        Node result = jobManagement.callExtension('withNestedContext', Mock(Item), StepContext, closure)

        then:
        isXmlIdentical('extension.xml', result)
    }

    def 'callExtension with environment'() {
        when:
        Node result = jobManagement.callExtension('withEnvironment', Mock(Item), StepContext, 'foo', 42, true)

        then:
        isXmlIdentical('extension.xml', result)
    }

    def 'callExtension with no value'() {
        when:
        Node result = jobManagement.callExtension('withNoValue', Mock(Item), StepContext)

        then:
        result == JobManagement.NO_VALUE
    }

    def 'callExtension for view'() {
        when:
        Node result = jobManagement.callExtension('myColumn', null, ColumnsContext)

        then:
        isXmlIdentical('view-extension.xml', result)
    }

    def 'call deprecated extension'() {
        when:
        jobManagement.callExtension('old', null, TriggerContext)

        then:
        buffer.toString() =~ /Warning: \(.+, line \d+\) old is deprecated/
    }

    def 'extension is being notified'() {
        when:
        jobManagement.createOrUpdateConfig(createItem('test-123', '/config.xml'), false)

        then:
        ContextExtensionPoint.all().get(TestContextExtensionPoint).isItemCreated('test-123')

        when:
        jobManagement.createOrUpdateConfig(createItem('test-123', '/config2.xml'), false)

        then:
        ContextExtensionPoint.all().get(TestContextExtensionPoint).isItemUpdated('test-123')
    }

    def 'create job with nonexisting parent'() {
        when:
        jobManagement.createOrUpdateConfig(createItem('nonexistingfolder/project', '/minimal-job.xml'), true)

        then:
        DslException e = thrown()
        e.message == 'Could not create item, unknown parent path in "nonexistingfolder/project"'
    }

    def 'create view with nonexisting parent'() {
        when:
        jobManagement.createOrUpdateView(
                'nonexistingfolder/view', loadResource('minimal-view.xml'), true
        )

        then:
        DslException e = thrown()
        e.message == 'Could not create view, unknown parent path in "nonexistingfolder/view"'
    }

    def 'rename job'() {
        setup:
        jenkinsRule.createFreeStyleProject('oldName')

        when:
        jobManagement.renameJobMatching('oldName', 'newName')

        then:
        jenkinsRule.jenkins.getItemByFullName('newName') != null
        jenkinsRule.jenkins.getItemByFullName('oldName') == null
    }

    def 'rename job relative to seed job'() {
        setup:
        Folder folder = jenkinsRule.jenkins.createProject(Folder, 'folder')
        folder.createProject(FreeStyleProject, 'oldName')
        FreeStyleProject seedJob = folder.createProject(FreeStyleProject, 'seed')
        AbstractBuild build = seedJob.scheduleBuild2(0).get()
        JobManagement jobManagement = new JenkinsJobManagement(
                new PrintStream(buffer), [:], build, build.workspace, LookupStrategy.SEED_JOB
        )

        when:
        jobManagement.renameJobMatching('oldName', 'newName')

        then:
        jenkinsRule.jenkins.getItemByFullName('folder/newName') != null
        jenkinsRule.jenkins.getItemByFullName('folder/oldName') == null
    }

    def 'rename job relative to seed job with absolute destination'() {
        setup:
        Folder folder = jenkinsRule.jenkins.createProject(Folder, 'folder')
        folder.createProject(FreeStyleProject, 'oldName')
        FreeStyleProject seedJob = folder.createProject(FreeStyleProject, 'seed')
        AbstractBuild build = seedJob.scheduleBuild2(0).get()
        JobManagement jobManagement = new JenkinsJobManagement(
                new PrintStream(buffer), [:], build, build.workspace, LookupStrategy.SEED_JOB
        )

        when:
        jobManagement.renameJobMatching('oldName', '/newName')

        then:
        jenkinsRule.jenkins.getItemByFullName('newName') != null
        jenkinsRule.jenkins.getItemByFullName('oldName') == null
    }

    def 'move Job to other Folder'() {
        setup:
        Folder oldFolder = jenkinsRule.jenkins.createProject(Folder, 'oldFolder')
        jenkinsRule.jenkins.createProject(Folder, 'newFolder')
        oldFolder.createProject(FreeStyleProject, 'oldName')

        when:
        jobManagement.renameJobMatching('oldFolder/oldName', 'newFolder/newName')

        then:
        jenkinsRule.jenkins.getItemByFullName('newFolder/newName') != null
        jenkinsRule.jenkins.getItemByFullName('oldFolder/oldName') == null
    }

    def 'move job to non-existing folder'() {
        setup:
        jenkinsRule.createFreeStyleProject('bar')

        when:
        jobManagement.renameJobMatching('bar', 'foo/bar')

        then:
        Exception e = thrown(DslException)
        e.message == 'Could not rename job bar to foo/bar, destination folder does not exist'
    }

    def 'createOrUpdateConfig relative to folder'() {
        setup:
        Folder folder = jenkinsRule.jenkins.createProject(Folder, 'folder')
        FreeStyleProject project = folder.createProject(FreeStyleProject, 'seed')
        AbstractBuild build = project.scheduleBuild2(0).get()
        JenkinsJobManagement jobManagement = new JenkinsJobManagement(
                new PrintStream(buffer), [:], build, build.workspace, LookupStrategy.SEED_JOB
        )

        when:
        jobManagement.createOrUpdateConfig(createItem('project', ('/minimal-job.xml')), true)

        then:
        jenkinsRule.jenkins.getItemByFullName('/folder/project') != null
    }

    def 'createOrUpdateConfig without build'() {
        when:
        testJobManagement.createOrUpdateConfig(createItem('project', ('/minimal-job.xml')), true)

        then:
        jenkinsRule.jenkins.getItemByFullName('/project') != null
    }

    def 'createOrUpdateConfig with absolute path'() {
        setup:
        Folder folder = jenkinsRule.jenkins.createProject(Folder, 'folder')
        FreeStyleProject project = folder.createProject(FreeStyleProject, 'seed')
        AbstractBuild build = project.scheduleBuild2(0).get()
        JenkinsJobManagement jobManagement = new JenkinsJobManagement(
                new PrintStream(buffer), [:], build, build.workspace, LookupStrategy.SEED_JOB
        )

        when:
        jobManagement.createOrUpdateConfig(createItem('/project', ('/minimal-job.xml')), true)

        then:
        jenkinsRule.jenkins.getItemByFullName('/project') != null
    }

    def 'createOrUpdateView relative to folder'() {
        setup:
        Folder folder = jenkinsRule.jenkins.createProject(Folder, 'folder')
        FreeStyleProject project = folder.createProject(FreeStyleProject, 'seed')
        AbstractBuild build = project.scheduleBuild2(0).get()
        JenkinsJobManagement jobManagement = new JenkinsJobManagement(
                new PrintStream(buffer), [:], build, build.workspace, LookupStrategy.SEED_JOB
        )

        when:
        jobManagement.createOrUpdateView('view', loadResource('minimal-view.xml'), true)

        then:
        folder.getView('view') != null
    }

    def 'createOrUpdateConfig with changing element order'() {
        when:
        jobManagement.createOrUpdateConfig(createItem('project', '/order-a.xml'), false)

        then:
        FreeStyleProject job = jenkinsRule.jenkins.getItemByFullName('project') as FreeStyleProject
        job.publishersList[0] instanceof ArtifactArchiver
        job.publishersList[1] instanceof Fingerprinter

        when:
        jobManagement.createOrUpdateConfig(createItem('project', '/order-b.xml'), false)

        then:
        job.publishersList[0] instanceof Fingerprinter
        job.publishersList[1] instanceof ArtifactArchiver
    }

    def 'createOrUpdateConfig skips update if identical'() {
        setup:
        SaveableListener saveableListener = Mock(SaveableListener)

        when:
        jobManagement.createOrUpdateConfig(createItem('project', '/config.xml'), false)

        then:
        FreeStyleProject job = jenkinsRule.jenkins.getItemByFullName('project') as FreeStyleProject
        SaveableListener.all().add(0, saveableListener)

        when:
        jobManagement.createOrUpdateConfig(createItem('project', '/config.xml'), false)

        then:
        0 * saveableListener.onChange(job, _)
    }

    def 'createOrUpdateConfig should fail if item is managed by another seed and failOnSeedCollision is enabled'() {
        setup:
        FreeStyleProject seedJob = jenkinsRule.createFreeStyleProject('seed')
        seedJob.buildersList.add(new ExecuteDslScripts('job("project")'))
        jenkinsRule.buildAndAssertSuccess(seedJob)
        FreeStyleProject seedJob2 = jenkinsRule.createFreeStyleProject('seed2')
        AbstractBuild build = seedJob2.scheduleBuild2(0).get()
        JobManagement jobManagement = new JenkinsJobManagement(
                new PrintStream(buffer), [:], build, build.workspace, LookupStrategy.JENKINS_ROOT
        )
        jobManagement.failOnSeedCollision = true

        when:
        jobManagement.createOrUpdateConfig(createItem('project', '/config.xml'), false)

        then:
        Exception e = thrown(DslException)
        e.message == 'Could not create item project, item is already managed by seed job seed'
    }

    def 'JENKINS-59995 seed in folder can update jobs in and out of folder'() {
        Folder folder = jenkinsRule.jenkins.createProject(Folder, 'folder')
        FreeStyleProject seedJob = folder.createProject(FreeStyleProject, 'seed')
        seedJob.buildersList.add(new ExecuteDslScripts('job("/project")'))
        seedJob.buildersList.add(new ExecuteDslScripts('job("/folder/project")'))
        jenkinsRule.buildAndAssertSuccess(seedJob)
        AbstractBuild build = seedJob.scheduleBuild2(0).get()
        JobManagement jobManagement = new JenkinsJobManagement(
                new PrintStream(buffer), [:], build, build.workspace, LookupStrategy.JENKINS_ROOT
        )
        jobManagement.failOnSeedCollision = true

        when:
        jobManagement.createOrUpdateConfig(createItem('/project', '/config.xml'), false)
        jobManagement.createOrUpdateConfig(createItem('/folder/project', '/config.xml'), false)

        then:
        jenkinsRule.jenkins.getItemByFullName('/project') != null
        jenkinsRule.jenkins.getItemByFullName('/folder/project') != null
    }

    def 'JENKINS-59995 two seeds should have collision on updating job in folder'() {
        setup:
        jenkinsRule.jenkins.createProject(Folder, 'folder')
        FreeStyleProject seedJob = jenkinsRule.createFreeStyleProject('seed')
        seedJob.buildersList.add(new ExecuteDslScripts('job("folder/project")'))
        jenkinsRule.buildAndAssertSuccess(seedJob)

        FreeStyleProject seedJob2 = jenkinsRule.createFreeStyleProject('seed2')
        AbstractBuild build = seedJob2.scheduleBuild2(0).get()
        JobManagement jobManagement = new JenkinsJobManagement(
                new PrintStream(buffer), [:], build, build.workspace, LookupStrategy.JENKINS_ROOT
        )
        jobManagement.failOnSeedCollision = true

        when:
        jobManagement.createOrUpdateConfig(createItem('folder/project', '/config.xml'), false)

        then:
        Exception e = thrown(DslException)
        e.message == 'Could not create item folder/project, item is already managed by seed job seed'
    }

    def 'createOrUpdateConfig should pass if item is managed by another seed and failOnSeedCollision is disabled'() {
        setup:
        FreeStyleProject seedJob = jenkinsRule.createFreeStyleProject('seed')
        seedJob.buildersList.add(new ExecuteDslScripts('job("project")'))
        jenkinsRule.buildAndAssertSuccess(seedJob)
        FreeStyleProject seedJob2 = jenkinsRule.createFreeStyleProject('seed2')
        AbstractBuild build = seedJob2.scheduleBuild2(0).get()
        JobManagement jobManagement = new JenkinsJobManagement(
                new PrintStream(buffer), [:], build, build.workspace, LookupStrategy.JENKINS_ROOT
        )

        when:
        jobManagement.createOrUpdateConfig(createItem('project', '/config.xml'), false)

        then:
        ContextExtensionPoint.all().get(TestContextExtensionPoint).isItemUpdated('project')
    }

    def 'createOrUpdateConfig should work if generated item was managed by a deleted seed'() {
        setup:
        FreeStyleProject seedJob = jenkinsRule.createFreeStyleProject('seed')
        seedJob.buildersList.add(new ExecuteDslScripts('job("project")'))
        jenkinsRule.buildAndAssertSuccess(seedJob)
        Jenkins.get().getItemByFullName('seed').delete()
        FreeStyleProject seedJob2 = jenkinsRule.createFreeStyleProject('seed2')
        AbstractBuild build = seedJob2.scheduleBuild2(0).get()
        JobManagement jobManagement = new JenkinsJobManagement(
                new PrintStream(buffer), [:], build, build.workspace, LookupStrategy.JENKINS_ROOT
        )

        when:
        jobManagement.createOrUpdateConfig(createItem('project', '/config.xml'), false)

        then:
        ContextExtensionPoint.all().get(TestContextExtensionPoint).isItemUpdated('project')
    }

    def 'createOrUpdateConfig should fail if item type does not match'() {
        setup:
        jenkinsRule.createFolder('my-job')

        when:
        jobManagement.createOrUpdateConfig(createItem('my-job', '/minimal-job.xml'), false)

        then:
        Exception e = thrown(DslException)
        e.message == 'Type of item "my-job" does not match existing type, item type can not be changed'
    }

    def 'createOrUpdateConfig should preserve credentials if they exist on a folder'() {
        setup:
        Folder folder = jenkinsRule.createProject(Folder, 'folder')
        folder.addProperty(createCredentialProperty())

        when:
        jobManagement.createOrUpdateConfig(createItem('folder', '/folder.xml'), false)

        then:
        def actual = jenkinsRule.jenkins.getItem('folder')
        actual.properties.size() == 1
    }

    def 'createOrUpdateConfig should ignore other properties on the folder'() {
        setup:
        Folder folder = jenkinsRule.createProject(Folder, 'folder')
        folder.addProperty(new FakeProperty())

        when:
        jobManagement.createOrUpdateConfig(createItem('folder', '/folder.xml'), false)

        then:
        def actual = jenkinsRule.jenkins.getItem('folder')
        actual.properties.size() == 0
    }

    def 'createOrUpdateView should work if view type changes'() {
        setup:
        jenkinsRule.jenkins.addView(new AllView('foo'))

        when:
        jobManagement.createOrUpdateView(
                'foo',
                JenkinsJobManagementSpec.getResourceAsStream('/javaposse/jobdsl/dsl/views/ListView-template.xml').text,
                false
        )

        then:
        noExceptionThrown()
        jenkinsRule.jenkins.getView('foo') != null
        jenkinsRule.jenkins.getView('foo').class == ListView
    }

    def isMinimumPluginVersionInstalled() {
        when:
        boolean result = jobManagement.isMinimumPluginVersionInstalled('script-security', '0.1')

        then:
        result

        when:
        result = jobManagement.isMinimumPluginVersionInstalled('script-security', '10.0')

        then:
        !result

        when:
        result = jobManagement.isMinimumPluginVersionInstalled('foo', '0.1')

        then:
        !result
    }

    def 'get vSphere cloud hash without vSphere cloud plugin'() {
        when:
        Integer hash = jobManagement.getVSphereCloudHash('test')

        then:
        hash == null
    }

    def 'read file from any workspace, job does not exist'() {
        when:
        String result = jobManagement.readFileInWorkspace(JOB_NAME, FILE_NAME)

        then:
        result == null
        buffer.toString().contains(FILE_NAME)
        buffer.toString().contains(JOB_NAME)
    }

    def 'read file from any workspace, no build, no workspace'() {
        setup:
        jenkinsRule.createFreeStyleProject(JOB_NAME)

        when:
        String result = jobManagement.readFileInWorkspace(JOB_NAME, FILE_NAME)

        then:
        result == null
        buffer.toString().contains(FILE_NAME)
        buffer.toString().contains(JOB_NAME)
    }

    def 'read file from any workspace, file does not exist'() {
        setup:
        jenkinsRule.buildAndAssertSuccess(jenkinsRule.createFreeStyleProject(JOB_NAME))

        when:
        String result = jobManagement.readFileInWorkspace(JOB_NAME, FILE_NAME)

        then:
        result == null
        buffer.toString().contains(FILE_NAME)
        buffer.toString().contains(JOB_NAME)
    }

    def 'read file from any workspace, file exists'() {
        setup:
        FreeStyleBuild build = jenkinsRule.buildAndAssertSuccess(jenkinsRule.createFreeStyleProject(JOB_NAME))
        build.workspace.child(FILE_NAME).write('hello', 'UTF-8')

        when:
        String result = jobManagement.readFileInWorkspace(JOB_NAME, FILE_NAME)

        then:
        result == 'hello'
    }

    def 'create view'() {
        when:
        jobManagement.createOrUpdateView('test-view', '<hudson.model.ListView/>', false)

        then:
        View view = jenkinsRule.instance.getView('test-view')
        view instanceof ListView
    }

    def 'create view without build'() {
        when:
        testJobManagement.createOrUpdateView('test-view', '<hudson.model.ListView/>', false)

        then:
        View view = jenkinsRule.instance.getView('test-view')
        view instanceof ListView
    }

    def 'update view'() {
        setup:
        jenkinsRule.instance.addView(new ListView('test-view'))

        when:
        jobManagement.createOrUpdateView(
                'test-view',
                '<hudson.model.ListView><description>lorem ipsum</description></hudson.model.ListView>',
                false
        )

        then:
        View view = jenkinsRule.instance.getView('test-view')
        view instanceof ListView
        view.description == 'lorem ipsum'
    }

    def 'update view ignoring changes'() {
        setup:
        jenkinsRule.instance.addView(new ListView('test-view'))

        when:
        jobManagement.createOrUpdateView(
                'test-view',
                '<hudson.model.ListView><description>lorem ipsum</description></hudson.model.ListView>',
                true
        )

        then:
        View view = jenkinsRule.instance.getView('test-view')
        view instanceof ListView
        view.description == null
    }

    def 'create view with invalid config'() {
        when:
        jobManagement.createOrUpdateView('test-view', '<hudson.model.ListView', false)

        then:
        thrown(DslException)
    }

    def 'readFileFromWorkspace with exception'() {
        setup:
        AbstractBuild build = jenkinsRule.buildAndAssertSuccess(jenkinsRule.createFreeStyleProject())
        jobManagement = new JenkinsJobManagement(System.out, [:], build, build.workspace, LookupStrategy.JENKINS_ROOT)
        String fileName = 'test.txt'

        when:
        jobManagement.readFileInWorkspace(fileName)

        then:
        Exception e = thrown(DslScriptException)
        e.message.contains(fileName)
    }

    def 'get all job permissions'() {
        setup:
        String propertyName = 'hudson.security.AuthorizationMatrixProperty'

        when:
        Set<String> permissions = jobManagement.getPermissions(propertyName)

        then:
        'hudson.model.Item.Delete' in permissions
        'hudson.model.Item.Configure' in permissions
        'hudson.model.Item.Read' in permissions
        'hudson.model.Item.Discover' in permissions
        'hudson.model.Item.Build' in permissions
        'hudson.model.Item.Workspace' in permissions
        'hudson.model.Item.Cancel' in permissions
        'hudson.model.Item.Move' in permissions
        'hudson.model.Run.Delete' in permissions
        'hudson.model.Run.Update' in permissions
        'hudson.scm.SCM.Tag' in permissions
    }

    def 'get all folder permissions'() {
        setup:
        String propertyName = 'com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty'

        when:
        Set<String> permissions = jobManagement.getPermissions(propertyName)

        then:
        'hudson.model.Item.Create' in permissions
        'hudson.model.Item.Delete' in permissions
        'hudson.model.Item.Configure' in permissions
        'hudson.model.Item.Read' in permissions
        'hudson.model.Item.Discover' in permissions
        'hudson.model.Item.Build' in permissions
        'hudson.model.Item.Workspace' in permissions
        'hudson.model.Item.Cancel' in permissions
        'hudson.model.Item.Move' in permissions
        'hudson.model.Run.Delete' in permissions
        'hudson.model.Run.Update' in permissions
    }

    def 'upload user content'(boolean ignoreExisting) {
        setup:
        UserContent userContent = new UserContent('foo.txt', new ByteArrayInputStream('foo'.bytes))

        when:
        jobManagement.createOrUpdateUserContent(userContent, ignoreExisting)

        then:
        jenkinsRule.instance.rootPath.child('userContent').child('foo.txt').exists()
        jenkinsRule.instance.rootPath.child('userContent').child('foo.txt').readToString() == 'foo'

        where:
        ignoreExisting << [true, false]
    }

    def 'update user content'() {
        setup:
        jenkinsRule.instance.rootPath.child('userContent').child('foo.txt').write('lorem ipsum', 'UTF-8')
        UserContent userContent = new UserContent('foo.txt', new ByteArrayInputStream('foo'.bytes))

        when:
        jobManagement.createOrUpdateUserContent(userContent, false)

        then:
        jenkinsRule.instance.rootPath.child('userContent').child('foo.txt').exists()
        jenkinsRule.instance.rootPath.child('userContent').child('foo.txt').readToString() == 'foo'
    }

    def 'do not update existing user content'() {
        setup:
        jenkinsRule.instance.rootPath.child('userContent').child('foo.txt').write('lorem ipsum', 'UTF-8')
        UserContent userContent = new UserContent('foo.txt', new ByteArrayInputStream('foo'.bytes))

        when:
        jobManagement.createOrUpdateUserContent(userContent, true)

        then:
        jenkinsRule.instance.rootPath.child('userContent').child('foo.txt').exists()
        jenkinsRule.instance.rootPath.child('userContent').child('foo.txt').readToString() == 'lorem ipsum'
    }

    @SuppressWarnings('BusyWait')
    @IgnoreIf({ os.isWindows() })
    def 'queue job'() {
        setup:
        FreeStyleProject project = jenkinsRule.createProject(FreeStyleProject, 'project')
        FreeStyleProject seedJob = jenkinsRule.createProject(FreeStyleProject, 'seed')
        AbstractBuild build = seedJob.scheduleBuild2(0).get()
        JobManagement jobManagement = new JenkinsJobManagement(
                new PrintStream(buffer), [:], build, build.workspace, LookupStrategy.JENKINS_ROOT
        )
        jenkinsRule.instance.quietPeriod = 0

        when:
        jobManagement.queueJob(project.name)
        while (project.builds.lastBuild == null) {
            Thread.sleep(100)
        }

        then:
        project.builds.lastBuild.causes.size() == 1
        project.builds.lastBuild.causes[0] instanceof Cause.UpstreamCause
        Cause.UpstreamCause upstreamCause = project.builds.lastBuild.causes[0] as Cause.UpstreamCause
        upstreamCause.upstreamBuild == 1
        upstreamCause.upstreamProject == 'seed'
    }

    @SuppressWarnings('BusyWait')
    @IgnoreIf({ os.isWindows() })
    def 'queue job without build'() {
        setup:
        FreeStyleProject project = jenkinsRule.createProject(FreeStyleProject, 'project')
        jenkinsRule.instance.quietPeriod = 0

        when:
        testJobManagement.queueJob(project.name)
        while (project.builds.lastBuild == null) {
            Thread.sleep(100)
        }

        then:
        project.builds.lastBuild.causes.size() == 1
        project.builds.lastBuild.causes[0].shortDescription == 'Started by a Job DSL script'
    }

    def 'parameters include SEED_JOB'() {
        when:
        Map<String, Object> parameters = jobManagement.parameters

        then:
        parameters.containsKey('SEED_JOB')
        parameters.SEED_JOB == seedJob
    }

    def 'parameters do not include SEED_JOB for testing'() {
        when:
        Map<String, Object> parameters = testJobManagement.parameters

        then:
        !parameters.containsKey('SEED_JOB')
    }

    def 'create nested view JENKINS-37450'() {
        setup:
        jobManagement.createOrUpdateView(
                'test', loadResource('javaposse/jobdsl/dsl/views/NestedView-template.xml'), false
        )

        when:
        jobManagement.createOrUpdateView(
                'test', loadResource('javaposse/jobdsl/dsl/views/NestedView-template.xml'), false
        )

        then:
        noExceptionThrown()
    }

    private static boolean isXmlIdentical(String expected, Node actual) throws Exception {
        XMLUnit.ignoreWhitespace = true
        XMLUnit.compareXML(loadResource(expected), nodeToString(actual)).identical()
    }

    private static String nodeToString(Node node) {
        StringWriter writer = new StringWriter()
        new XmlNodePrinter(new PrintWriter(writer)).print(node)
        writer.toString()
    }

    private static String loadResource(String resourceName) {
        Resources.toString(Resources.getResource(resourceName), UTF_8)
    }

    private static FolderCredentialsProvider.FolderCredentialsProperty createCredentialProperty() {
        UsernamePasswordCredentialsImpl  credentials = new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, '',
            '', '', '')
        DomainCredentials[] domainCredentials = new DomainCredentials[1]
        domainCredentials[0] = new DomainCredentials(Domain.global(), Collections.singletonList(credentials))

        new FolderCredentialsProvider.FolderCredentialsProperty(domainCredentials)
    }

    private static class FakeProperty extends AbstractFolderProperty<AbstractFolder<?>> {

        @Extension(optional = true)
            static class DescriptorImpl extends AbstractFolderPropertyDescriptor {
                final String displayName = ''
            }
    }

    private Item createItem(String name, String config) {
        new Item(jobManagement, name) {
            @Override
            Node getNodeTemplate() {
                new XmlParser().parse(JenkinsJobManagementSpec.getResourceAsStream(config))
            }
        }
    }
}
