/*
 * Copyright (c) 2010 iceScrum Technologies.
 *
 * This file is part of iceScrum.
 *
 * iceScrum is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * iceScrum is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with iceScrum.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Authors:
 *
 * Vincent Barrier (vbarrier@kagilum.com)
 * Stéphane Maldini (stephane.maldini@icescrum.com)
 * Manuarii Stein (manuarii.stein@icescrum.com)
 *
 */

package org.icescrum.web.presentation

import org.springframework.web.servlet.support.RequestContextUtils as RCU

import grails.converters.JSON
import grails.plugins.springsecurity.Secured
import org.apache.commons.io.FilenameUtils
import org.icescrum.core.domain.Product
import org.icescrum.core.domain.User
import org.icescrum.core.support.ProgressSupport
import org.icescrum.web.upload.AjaxMultipartResolver
import org.springframework.mail.MailException
import org.springframework.security.access.AccessDeniedException
import org.icescrum.core.domain.Sprint
import grails.plugin.springcache.annotations.Cacheable

class ScrumOSController {

    def springSecurityService
    def productService
    def teamService
    def userService
    def menuBarSupport
    def notificationEmailService
    def securityService

    def index = {
        def currentUserInstance = null

        def locale = params.lang ?: null
        try {
            def localeAccept = request.getHeader("accept-language")?.split(",")
            if (localeAccept)
                localeAccept = localeAccept[0]?.split("-")

            if (localeAccept?.size() > 0) {
                locale = params.lang ?: localeAccept[0].toString()
            }
        } catch (Exception e) {}

        if (springSecurityService.isLoggedIn()) {
            currentUserInstance = User.get(springSecurityService.principal.id)
            if (locale != currentUserInstance.preferences.language || RCU.getLocale(request).toString() != currentUserInstance.preferences.language) {
                RCU.getLocaleResolver(request).setLocale(request, response, new Locale(currentUserInstance.preferences.language))
            }
        } else {
            if (locale) {
                RCU.getLocaleResolver(request).setLocale(request, response, new Locale(locale))
            }
        }
        def currentProductInstance = params.product ? Product.get(params.long('product')) : null

        if (currentProductInstance?.preferences?.hidden && !securityService.inProduct(currentProductInstance, springSecurityService.authentication) && !securityService.stakeHolder(currentProductInstance,springSecurityService.authentication,false)){
            redirect(action:'error403',controller:'errors')
            return
        }

        [user: currentUserInstance,
                lang: RCU.getLocale(request).toString().substring(0, 2),
                product: currentProductInstance,
                publicProductsExists: Product.count() ? true : false,
                productFilteredsList: productService.getByMemberProductList()]
    }


    def openWidget = {
        if (!params.window) {
            render(status: 400, contentType: 'application/json', text: [notice: [text: message(code: 'is.error.no.widget')]] as JSON)
            return
        }


        def controllerRequested = "${params.window}Controller"
        def controller = grailsApplication.uIControllerClasses.find {
            it.shortName.toLowerCase() == controllerRequested.toLowerCase()
        }
        if (controller) {
            def paramsWidget = null
            if (params.product) {
                paramsWidget = [product: params.product]
            }
            def url = createLink(controller: params.window, action: controller.getPropertyValue('widget')?.init ?: 'indexWidget', params: paramsWidget).toString() - request.contextPath
            if (!menuBarSupport.permissionDynamicBar(url)) {
                session['widgetsList'].remove(params.window)
                render(status: 400)
                return
            }

            if (!
            session['widgetsList']?.contains(params.window)) {
                session['widgetsList'] = session['widgetsList'] ?: []
                session['widgetsList'].add(params.window)
            }
            render is.widget([
                    id: params.window,
                    hasToolbar: controller.getPropertyValue('widget')?.toolbar ?: false,
                    closeable: (controller.getPropertyValue('widget')?.closeable == null) ? true : controller.getPropertyValue('widget').closeable,
                    sortable: (controller.getPropertyValue('widget')?.sortable == null) ? true : controller.getPropertyValue('widget').sortable,
                    windowable: controller.getPropertyValue('window') ? true : false,
                    height: controller.getPropertyValue('widget')?.height ?: false,
                    hasTitleBarContent: controller.getPropertyValue('widget')?.titleBarContent ?: false,
                    title: message(code: controller.getPropertyValue('widget')?.title ?: ''),
                    init: controller.getPropertyValue('widget')?.init ?: 'indexWidget',
            ], {})
        }
    }

    def closeWindow = {
        session['currentWindow'] = null
        render(status: '200')
    }

    def closeWidget = {
        if (session['widgetsList']?.contains(params.window))
            session['widgetsList'].remove(params.window);
        render(status: 200)
    }

    def openWindow = {
        if (!params.window) {
            render(status: 400, contentType: 'application/json', text: [notice: [text: message(code: 'is.error.no.window')]] as JSON)
            return
        }
        session['currentWindow'] = params.window
        session['currentView'] = session['currentView'] ?: springSecurityService.isLoggedIn() ? 'postitsView' : 'tableView'

        if (session['widgetsList']?.contains(params.window))
            session['widgetsList'].remove(params.window);

        def controllerRequested = "${params.window}Controller"
        def controller = grailsApplication.uIControllerClasses.find {
            it.shortName.toLowerCase() == controllerRequested.toLowerCase()
        }
        if (controller) {

            def param = [:]
            if (params.product)
                param = [product: params.product]
            def url = createLink(controller: params.window, action: params.actionWindow ?: controller.getPropertyValue('window').init ?: 'index', params: param).toString() - request.contextPath

            if (!menuBarSupport.permissionDynamicBar(url))
                throw new AccessDeniedException('denied')

            render is.window([
                    window: params.window,
                    title: message(code: controller.getPropertyValue('window')?.title ?: ''),
                    help: message(code: controller.getPropertyValue('window')?.help ?: null),
                    shortcuts: controller.getPropertyValue('shortcuts') ?: null,
                    hasToolbar: (controller.getPropertyValue('window')?.toolbar != null) ? controller.getPropertyValue('window')?.toolbar : true,
                    hasTitleBarContent: controller.getPropertyValue('window')?.titleBarContent ?: false,
                    maximizeable: controller.getPropertyValue('window')?.maximizeable ?: true,
                    closeable: (controller.getPropertyValue('window')?.closeable == null) ? true : controller.getPropertyValue('widget').closeable,
                    widgetable: controller.getPropertyValue('widget') ? true : false,
                    init: params.actionWindow ?: controller.getPropertyValue('window').init ?: 'index',
            ], {})
        }
    }

    def reloadToolbar = {
        if (!params.window) {
            render(status: 400, contentType: 'application/json', text: [notice: [text: message(code: 'is.error.no.window.toolbar')]] as JSON)
            return
        }
        def controllerRequested = "${params.window}Controller"
        def controller = grailsApplication.uIControllerClasses.find {
            it.shortName.toLowerCase() == controllerRequested.toLowerCase()
        }
        if (controller) {
            forward(controller: params.window, action: 'toolbar', params: params)
        }
    }

    def changeView = {
        if (!params.view) return
        session['currentView'] = params.view
        forward(action: params.actionWindow, controller: params.window, params: [product: params.product, id: params.id ?: null])
    }

    @Secured('isAuthenticated()')
    def upload = {
        def upfile = request.getFile('file')
        def filename = FilenameUtils.getBaseName(upfile.originalFilename)
        def ext = FilenameUtils.getExtension(upfile.originalFilename)
        def tmpF = session.createTempFile(filename, '.' + ext)
        request.getFile("file").transferTo(tmpF)
        if (!session.uploadedFiles)
            session.uploadedFiles = [:]
        session.uploadedFiles["${params."X-Progress-ID"}"] = tmpF.toString()
        log.info "upload done for session: ${session?.id} / fileID: ${params."X-Progress-ID"}"
        render(status: 200)
    }

    @Secured('isAuthenticated()')
    def uploadStatus = {
        log.debug "upload status for session: ${session?.id} / fileID: ${params?."X-Progress-ID" ?: 'null'}"
        if (params."X-Progress-ID" && session[AjaxMultipartResolver.progressAttrName(params."X-Progress-ID")]) {
            if (((ProgressSupport) session[AjaxMultipartResolver.progressAttrName(params."X-Progress-ID")])?.complete) {
                render(status: 200, contentType: 'application/json', text: session[AjaxMultipartResolver.progressAttrName(params."X-Progress-ID")] as JSON)
                session.removeAttribute([AjaxMultipartResolver.progressAttrName(params."X-Progress-ID")])
            } else {
                render(status: 200, contentType: 'application/json', text: session[AjaxMultipartResolver.progressAttrName(params."X-Progress-ID")] as JSON)
            }
        } else {
            render(status: 400, contentType: 'application/json', text: [notice: [text: message(code: 'is.upload.error')]] as JSON)
        }
    }

    @Cacheable(cache = 'applicationCache', keyGenerator="localeKeyGenerator")
    def about = {
        def locale = RCU.getLocale(request)
        def file = new File(grailsAttributes.getApplicationContext().getResource("/infos").getFile().toString() + File.separatorChar + "about_${locale}.xml")
        if (!file.exists()) {
            file = new File(grailsAttributes.getApplicationContext().getResource("/infos").getFile().toString() + File.separatorChar + "about_en.xml")
        }
        def aboutXml = new XmlSlurper().parse(file)
        def license
        render(status: 200, template: "about/index", model: [id: controllerName, about: aboutXml])
    }

    def textileParser = {
        if (params.truncate) {
            params.data = is.truncated([size: params.int('truncate')], params.data)
        }
        if (params.withoutHeader) {
            render(text: wikitext.renderHtml([markup: "Textile"], params.data))
        } else {
            render(status: 200, template: 'textileParser')
        }
    }

    def reportError = {
        try {
            notificationEmailService.send([
                    to: grailsApplication.config.icescrum.alerts.errors.to,
                    subject: "[iceScrum][report] Rapport d'erreur",
                    view: '/emails-templates/reportError',
                    model: [error: params.stackError,
                            comment: params.comments,
                            appID: grailsApplication.config.icescrum.appID,
                            ip: request.getHeader('X-Forwarded-For') ?: request.getRemoteAddr(),
                            date: g.formatDate(date: new Date(), formatName: 'is.date.format.short.time'),
                            version: g.meta(name: 'app.version')]
            ]);
            render(status: 200, contentType: 'application/json', text: [notice: [text: message(code: 'is.blame.sended'), type: 'notice']] as JSON)
        } catch (MailException e) {
            render(status: 400, contentType: 'application/json', text: [notice: [text: message(code: 'is.mail.error')]] as JSON)
            return
        } catch (RuntimeException re) {
            render(status: 400, contentType: 'application/json', text: [notice: [text: message(code: re.getMessage())]] as JSON)
            return
        } catch (Exception e) {
            render(status: 400, contentType: 'application/json', text: [notice: [text: message(code: 'is.mail.error')]] as JSON)
            return
        }
    }

    @Cacheable(cache = 'projectCache', keyGenerator = 'projectUserKeyGenerator')
    def templates = {
        def currentSprint = null
        def product = null
        if (params.long('product')) {
            product = Product.get(params.product)
            currentSprint = Sprint.findCurrentSprint(product.id).list()[0] ?: null
        }
        def tmpl = g.render(
                template: 'templatesJS',
                model: [id: controllerName,
                        currentSprint: currentSprint,
                        product:product
                ])

        tmpl = "${tmpl}".split("<div class='templates'>")
        tmpl[1] = tmpl[1].replaceAll('%3F', '?').replaceAll('%3D', '=').replaceAll('<script type="text/javascript">', '<js>').replaceAll('</script>', '</js>').replaceAll('<template ', '<script type="text/x-jqote-template" ').replaceAll('</template>', '</script>')
        render(text: tmpl[0] + '<div class="templates">' + tmpl[1], status: 200)
    }
}
