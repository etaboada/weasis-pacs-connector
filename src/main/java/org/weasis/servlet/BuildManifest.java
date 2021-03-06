/*******************************************************************************
 * Copyright (c) 2010 Weasis Team.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Nicolas Roduit - initial API and implementation
 ******************************************************************************/

package org.weasis.servlet;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.zip.GZIPOutputStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.dicom.DicomNode;
import org.weasis.launcher.wado.Patient;
import org.weasis.launcher.wado.WadoParameters;
import org.weasis.launcher.wado.WadoQuery;
import org.weasis.launcher.wado.WadoQueryException;
import org.weasis.launcher.wado.xml.Base64;
import org.weasis.launcher.wado.xml.TagUtil;

public class BuildManifest extends HttpServlet {
    /**
     * Logger for this class
     */
    private static final Logger logger = LoggerFactory.getLogger(BuildManifest.class);

    static final String PatientID = "patientID";
    static final String StudyUID = "studyUID";
    static final String AccessionNumber = "accessionNumber";
    static final String SeriesUID = "seriesUID";
    static final String ObjectUID = "objectUID";

    /**
     * Constructor of the object.
     */
    public BuildManifest() {
        super();
    }

    /**
     * Initialization of the servlet. <br>
     * 
     * @throws ServletException
     *             if an error occurs
     */
    @Override
    public void init() throws ServletException {
    }

    /**
     * The doGet method of the servlet. <br>
     * 
     * This method is called when a form has its tag value method equals to get.
     * 
     * @param request
     *            the request send by the client to the server
     * @param response
     *            the response send by the server to the client
     * @throws ServletErrorException
     * @throws IOException
     * @throws ServletException
     * @throws ServletException
     *             if an error occurred
     * @throws IOException
     *             if an error occurred
     */

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Properties pacsProperties = WeasisLauncher.pacsProperties;
        // Test if this client is allowed
        String hosts = pacsProperties.getProperty("hosts.allow");
        if (hosts != null && !hosts.trim().equals("")) {
            String clientHost = request.getRemoteHost();
            String clientIP = request.getRemoteAddr();
            boolean accept = false;
            for (String host : hosts.split(",")) {
                if (host.equals(clientHost) || host.equals(clientIP)) {
                    accept = true;
                    break;
                }
            }
            if (!accept) {
                logger.warn("The request from {} is not allowed.", clientHost);
                return;
            }
        }

        try {
            logRequestInfo(request);

            String baseURL = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
            System.setProperty("server.base.url", baseURL);

            Properties dynamicProps = (Properties) pacsProperties.clone();
            // Perform variable substitution for system properties.
            for (Enumeration e = pacsProperties.propertyNames(); e.hasMoreElements();) {
                String name = (String) e.nextElement();
                dynamicProps.setProperty(name,
                    TagUtil.substVars(pacsProperties.getProperty(name), name, null, pacsProperties));
            }

            String wadoQueriesURL = dynamicProps.getProperty("pacs.wado.url", "http://localhost:8080/wado");
            String pacsAET = dynamicProps.getProperty("pacs.aet", "DCM4CHEE");
            String pacsHost = dynamicProps.getProperty("pacs.host", "localhost");
            int pacsPort = Integer.parseInt(dynamicProps.getProperty("pacs.port", "11112"));
            DicomNode dicomSource = new DicomNode(pacsAET, pacsHost, pacsPort);
            String componentAET = dynamicProps.getProperty("aet", "WEASIS");
            boolean acceptNoImage = Boolean.valueOf(dynamicProps.getProperty("accept.noimage"));
            List<Patient> patients = WeasisLauncher.getPatientList(request, dicomSource, componentAET);

            if ((patients == null || patients.size() < 1) && !acceptNoImage) {
                logger.warn("No data has been found!");
                response.sendError(HttpServletResponse.SC_NO_CONTENT, "No data has been found!");
                return;
            }

            // If the web server requires an authentication (pacs.web.login=user:pwd)
            String webLogin = dynamicProps.getProperty("pacs.web.login", null);
            if (webLogin != null) {
                webLogin = Base64.encodeBytes(webLogin.trim().getBytes());
            }
            boolean onlysopuid = Boolean.valueOf(dynamicProps.getProperty("wado.onlysopuid"));
            String addparams = dynamicProps.getProperty("wado.addparams", "");
            String overrideTags = dynamicProps.getProperty("wado.override.tags", null);
            String httpTags = dynamicProps.getProperty("wado.httpTags", null);

            WadoParameters wado = new WadoParameters(wadoQueriesURL, onlysopuid, addparams, overrideTags, webLogin);
            if (httpTags != null && !httpTags.trim().equals("")) {
                for (String tag : httpTags.split(",")) {
                    String[] val = tag.split(":");
                    if (val.length == 2) {
                        wado.addHttpTag(val[0].trim(), val[1].trim());
                    }
                }
            }
            WadoQuery wadoQuery =
                new WadoQuery(patients, wado, dynamicProps.getProperty("pacs.db.encoding", "utf-8"), acceptNoImage);

            if (request.getParameter("gzip") != null) {
                response.setContentType("application/x-gzip");
                Closeable stream = null;
                GZIPOutputStream gz = null;
                try {
                    stream = response.getOutputStream();
                    gz = new GZIPOutputStream((OutputStream) stream);
                    gz.write(wadoQuery.toString().getBytes());
                } finally {
                    if (gz != null) {
                        gz.close();
                    }
                    if (stream != null) {
                        stream.close();
                    }
                }
            } else {
                response.setContentType("text/xml");
                PrintWriter outWriter = response.getWriter();
                outWriter.print(wadoQuery.toString());
                outWriter.close();
            }

        } catch (Exception e) {
            logger.error("doGet(HttpServletRequest, HttpServletResponse)", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        } catch (WadoQueryException e) {
            logger.error("doGet(HttpServletRequest, HttpServletResponse)", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * The doPost method of the servlet. <br>
     * 
     * This method is called when a form has its tag value method equals to post.
     * 
     * @param request
     *            the request send by the client to the server
     * @param response
     *            the response send by the server to the client
     * @throws ServletException
     *             if an error occurred
     * @throws IOException
     *             if an error occurred
     */
    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        doGet(request, response);
    }

    @Override
    protected void doHead(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            response.setContentType("text/xml");

        } catch (Exception e) {
            logger.error("doHead(HttpServletRequest, HttpServletResponse)", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Destruction of the servlet. <br>
     */
    @Override
    public void destroy() {
        super.destroy();
    }

    /**
     * @param request
     */
    protected void logRequestInfo(HttpServletRequest request) {
        logger.debug("logRequestInfo(HttpServletRequest) - getRequestQueryURL : {}{}", request.getRequestURL()
            .toString(), request.getQueryString() != null ? ("?" + request.getQueryString().trim()) : "");
        logger.debug("logRequestInfo(HttpServletRequest) - getContextPath : {}", request.getContextPath());
        logger.debug("logRequestInfo(HttpServletRequest) - getRequestURI : {}", request.getRequestURI());
        logger.debug("logRequestInfo(HttpServletRequest) - getServletPath : {}", request.getServletPath());
    }

}
