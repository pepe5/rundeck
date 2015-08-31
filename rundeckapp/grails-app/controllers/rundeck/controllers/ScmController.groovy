package rundeck.controllers

import com.dtolabs.rundeck.core.authorization.AuthContext
import com.dtolabs.rundeck.server.authorization.AuthConstants
import rundeck.ScheduledExecution

class ScmController extends ControllerBase {
    def scmService
    def frameworkService

    def static allowedMethods = [
            disable: ['POST'],
    ]

    def index(String project) {
        def pluginConfig = scmService.loadScmConfig(project, 'export')
        def plugins = scmService.listPlugins('export')
        def configuredPlugin = null
        if (pluginConfig?.type) {
            configuredPlugin = scmService.getExportPluginDescriptor(pluginConfig.type)
        }
        return [
                plugins         : plugins ?: [],
                configuredPlugin: configuredPlugin,
                pluginConfig    : pluginConfig,
                config          : pluginConfig?.config,
                type            : pluginConfig?.type
        ]
    }

    def setup(String project, String type) {
        if (scmService.projectHasConfiguredExportPlugin(project)) {
            return redirect(action: 'index', params: [project: project])
        }
        def describedPlugin = scmService.getExportPluginDescriptor(type)
        [properties: scmService.getExportSetupProperties(project, type), type: type, plugin: describedPlugin]
    }

    def saveSetup(String integration, String project, String type) {

        AuthContext authContext = frameworkService.getAuthContextForSubjectAndProject(session.subject, project)
        if (unauthorizedResponse(
                frameworkService.authorizeApplicationResourceAll(
                        authContext,
                        frameworkService.authResourceForProject(project),
                        [AuthConstants.ACTION_CONFIGURE, AuthConstants.ACTION_ADMIN]
                ),
                AuthConstants.ACTION_CONFIGURE, 'Project', project
        )) {
            return
        }

        def config = params.config

        boolean valid = false
        //cancel modification
        if (params.cancel == 'Cancel') {
            return redirect(controller: 'scm', action: 'index', params: [project: project])
        }

        withForm {
            valid = true
        }.invalidToken {
            request.errorCode = 'request.error.invalidtoken.message'
            renderErrorView([:])
        }
        if (!valid) {
            return
        }

        //require type param
        def result = scmService.savePlugin(integration, project, type, config)
        def report
        if (!result.valid) {
            report = result.report
            request.error = message(code:"some.input.values.were.not.valid")
            log.error("configuration error: " + report)

            def describedPlugin = scmService.getExportPluginDescriptor(type)

            render view: 'setup',
                   model: [
                           properties: scmService.getExportSetupProperties(project, type),
                           type      : type,
                           plugin    : describedPlugin,
                           report    : report,
                           config    : config
                   ]

        } else {
            flash.message = "setup complete"
            redirect(action: 'index', params: [project: project])
        }
    }

    def disable(String integration, String project, String type) {

        AuthContext authContext = frameworkService.getAuthContextForSubjectAndProject(session.subject, project)
        if (unauthorizedResponse(
                frameworkService.authorizeApplicationResourceAll(
                        authContext,
                        frameworkService.authResourceForProject(project),
                        [AuthConstants.ACTION_CONFIGURE, AuthConstants.ACTION_ADMIN]
                ),
                AuthConstants.ACTION_CONFIGURE, 'Project', project
        )) {
            return
        }

        boolean valid = false
        //cancel modification
        if (params.cancel == 'Cancel') {
            return redirect(controller: 'scm', action: 'index', params: [project: project])
        }

        withForm {
            valid = true
        }.invalidToken {
            request.errorCode = 'request.error.invalidtoken.message'
            renderErrorView([:])
        }
        if (!valid) {
            return
        }

        //require type param
        scmService.disablePlugin(integration, project, type)

        flash.message = "Plugin disabled for SCM ${integration}: ${type}"
        redirect(action: 'index', params: [project: project])
    }

    def enable(String integration, String project, String type) {

        AuthContext authContext = frameworkService.getAuthContextForSubjectAndProject(session.subject, project)
        if (unauthorizedResponse(
                frameworkService.authorizeApplicationResourceAll(
                        authContext,
                        frameworkService.authResourceForProject(project),
                        [AuthConstants.ACTION_CONFIGURE, AuthConstants.ACTION_ADMIN]
                ),
                AuthConstants.ACTION_CONFIGURE, 'Project', project
        )) {
            return
        }

        boolean valid = false
        //cancel modification
        if (params.cancel == 'Cancel') {
            return redirect(controller: 'scm', action: 'index', params: [project: project])
        }

        withForm {
            valid = true
        }.invalidToken {
            request.errorCode = 'request.error.invalidtoken.message'
            renderErrorView([:])
        }
        if (!valid) {
            return
        }

        //require type param
        def result = scmService.enablePlugin(integration, project, type)
        if (result.valid) {
            flash.message = "Plugin enabled for SCM ${integration}: ${type}"

        } else {
            flash.warning = "Plugin was not enabled for SCM ${integration}: ${type}: Configuration was not valid.  Please reconfigure and try again."
        }

        redirect(action: 'index', params: [project: project])
    }

    def commit(String project) {
        if (!scmService.projectHasConfiguredExportPlugin(project)) {
            return redirect(action: 'index', params: [project: project])
        }
        List<String> jobIds = []
        if (params.jobIds) {
            jobIds = [params.jobIds].flatten()
        } else if (params.allJobs) {
            jobIds = ScheduledExecution.findAllByProject(params.project).collect {
                it.extid
            }
        }
        List<ScheduledExecution> jobs = jobIds.collect {
            ScheduledExecution.getByIdOrUUID(it)
        }
        def scmStatus = scmService.exportStatusForJobs(jobs)
        def scmFiles = scmService.filePathsMapForJobRefs(scmService.jobRefsForJobs(jobs))
        [
                properties: scmService.getExportCommitProperties(project, jobIds),
                jobs      : jobs,
                scmStatus : scmStatus,
                selected  : params.jobIds ? jobIds : [],
                filesMap  : scmFiles
        ]
    }

    def saveCommit(String project) {

        AuthContext authContext = frameworkService.getAuthContextForSubjectAndProject(session.subject, project)
        if (unauthorizedResponse(
                frameworkService.authorizeApplicationResourceAll(
                        authContext,
                        frameworkService.authResourceForProject(project),
                        [AuthConstants.ACTION_CONFIGURE, AuthConstants.ACTION_ADMIN]
                ),
                AuthConstants.ACTION_CONFIGURE, 'Project', project
        )) {
            return
        }

        if (!scmService.projectHasConfiguredExportPlugin(project)) {
            return redirect(action: 'index', params: [project: project])
        }
        boolean valid = false
        //cancel modification
        if (params.cancel == 'Cancel') {
            return redirect(controller: 'scm', action: 'index', params: [project: project])
        }

        withForm {
            valid = true
        }.invalidToken {
            request.errorCode = 'request.error.invalidtoken.message'
            renderErrorView([:])
        }
        if (!valid) {
            return
        }

        if (!params.jobIds) {
            flash.message = "No Job Ids Selected"
            return redirect(action: 'index', params: [project: project])
        }
        List<String> jobIds = [params.jobIds].flatten()

        List<ScheduledExecution> jobs = jobIds.collect {
            ScheduledExecution.getByIdOrUUID(it)
        }

        def result = scmService.exportCommit(project, params.commit, jobs)
        if (!result.valid) {
            def report = result.report
            request.error = message(code:"some.input.values.were.not.valid")
            log.debug("configuration error: " + report)

            def scmStatus = scmService.exportStatusForJobs(jobs)
            def scmFiles = scmService.filePathsMapForJobRefs(scmService.jobRefsForJobs(jobs))
            render view: 'commit',
                   model: [
                           properties: scmService.getExportCommitProperties(project, jobIds),
                           jobs      : jobs,
                           scmStatus : scmStatus,
                           selected  : params.jobIds ? jobIds : [],
                           filesMap  : scmFiles,
                           report    : report,
                           config    : params.commit
                   ]
            return
        }

        def commitid = result.commitId
        def code=jobs.size()>1?"scmController.action.commit.multi.succeed.message":"scmController.action.commit.succeed.message"
        flash.message = message(code: code, args: [commitid,jobs.size(),'{{Job '+jobIds[0]+'}}'])
        redirect(action: 'jobs', controller: 'menu', params: [project: params.project])
    }

    /**
     * Ajax endpoint for job diff
     */
    def diffRemote(String project, String jobId) {
        if (!scmService.projectHasConfiguredExportPlugin(project)) {
            return redirect(action: 'index', params: [project: project])
        }
        if (!jobId) {
            flash.message = "No jobId Selected"
            return redirect(action: 'index', params: [project: project])
        }
        def job = ScheduledExecution.getByIdOrUUID(jobId)
        def diff = scmService.exportDiff(project, job)
        render(contentType: 'application/json') {
            modified = diff?.modified?:false
            newNotFound = diff?.newNotFound ?: false
            oldNotFound = diff?.oldNotFound ?: false
            content = diff?.content ?: ''
        }
    }

    def diff(String project, String jobId) {
        if (!scmService.projectHasConfiguredExportPlugin(project)) {
            return redirect(action: 'index', params: [project: project])
        }
        if (!jobId) {
            flash.message = "No jobId Selected"
            return redirect(action: 'index', params: [project: project])
        }
        def job = ScheduledExecution.getByIdOrUUID(jobId)
        def scmStatus = scmService.exportStatusForJobs([job])
        def scmFilePaths = scmService.filePathsMapForJobs([job])
        def diffResult = scmService.exportDiff(project, job)
        withFormat {
            html {
                [diffResult: diffResult, scmStatus: scmStatus, job: job, scmFilePaths: scmFilePaths]
            }
            text {
                render(contentType: 'text/plain', text: diffResult?.content?:'')
            }
        }
    }
}