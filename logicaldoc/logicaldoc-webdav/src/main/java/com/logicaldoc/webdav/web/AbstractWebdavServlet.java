package com.logicaldoc.webdav.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpHeaders;
import org.apache.jackrabbit.webdav.DavConstants;
import org.apache.jackrabbit.webdav.DavException;
import org.apache.jackrabbit.webdav.DavLocatorFactory;
import org.apache.jackrabbit.webdav.DavMethods;
import org.apache.jackrabbit.webdav.DavResource;
import org.apache.jackrabbit.webdav.DavServletRequest;
import org.apache.jackrabbit.webdav.DavServletResponse;
import org.apache.jackrabbit.webdav.MultiStatus;
import org.apache.jackrabbit.webdav.MultiStatusResponse;
import org.apache.jackrabbit.webdav.WebdavRequest;
import org.apache.jackrabbit.webdav.WebdavRequestImpl;
import org.apache.jackrabbit.webdav.WebdavResponse;
import org.apache.jackrabbit.webdav.WebdavResponseImpl;
import org.apache.jackrabbit.webdav.io.InputContext;
import org.apache.jackrabbit.webdav.io.InputContextImpl;
import org.apache.jackrabbit.webdav.io.OutputContext;
import org.apache.jackrabbit.webdav.io.OutputContextImpl;
import org.apache.jackrabbit.webdav.property.DavPropertyName;
import org.apache.jackrabbit.webdav.property.DavPropertyNameIterator;
import org.apache.jackrabbit.webdav.property.DavPropertyNameSet;
import org.apache.jackrabbit.webdav.property.PropEntry;
import org.apache.jackrabbit.webdav.search.SearchConstants;
import org.apache.jackrabbit.webdav.search.SearchResource;
import org.apache.jackrabbit.webdav.security.AclResource;
import org.apache.jackrabbit.webdav.version.DeltaVResource;
import org.apache.jackrabbit.webdav.version.OptionsInfo;
import org.apache.jackrabbit.webdav.version.OptionsResponse;
import org.apache.jackrabbit.webdav.version.VersionControlledResource;
import org.apache.jackrabbit.webdav.version.VersionableResource;
import org.apache.jackrabbit.webdav.version.report.Report;
import org.apache.jackrabbit.webdav.version.report.ReportInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.User;
import com.logicaldoc.core.security.dao.UserDAO;
import com.logicaldoc.util.Context;
import com.logicaldoc.webdav.context.ExportContext;
import com.logicaldoc.webdav.resource.DavResourceFactory;
import com.logicaldoc.webdav.resource.DavResourceImpl;
import com.logicaldoc.webdav.session.DavSession;
import com.logicaldoc.webdav.session.DavSessionImpl;

/**
 * A base for building a WebDAV servlet
 */
abstract public class AbstractWebdavServlet extends HttpServlet implements DavConstants {

	protected static ServletContext context;

	protected static Logger log = LoggerFactory.getLogger(AbstractWebdavServlet.class);

	private static final long serialVersionUID = -8726695805361483901L;

	private static final String RANGE_BYTE_PREFIX = "bytes=";

	private static final char RANGE_SET_SEP = ',';

	private static final char RANGE_SEP = '-';

	/**
	 * Checks if the precondition for this request and resource is valid.
	 * 
	 * @param request the HTTP request
	 * @param response the server's response
	 * 
	 * @return if the precondition is valid
	 */
	abstract protected boolean isPreconditionValid(WebdavRequest request, DavResource resource);

	/**
	 * Returns the <code>DavLocatorFactory</code>
	 * 
	 * @return the locator factory
	 */
	abstract public DavLocatorFactory getLocatorFactory();

	/**
	 * Sets the <code>DavLocatorFactory</code>
	 * 
	 * @param locatorFactory local factory
	 */
	abstract public void setLocatorFactory(DavLocatorFactory locatorFactory);

	/**
	 * Returns the <code>DavResourceFactory</code>
	 * 
	 * @return the resource factory
	 */
	abstract public DavResourceFactory getResourceFactory();

	/**
	 * Sets the <code>DavResourceFactory</code>
	 * 
	 * @param resourceFactory resource factory
	 */
	abstract public void setResourceFactory(DavResourceFactory resourceFactory);

	/**
	 * Returns the value of the 'WWW-Authenticate' header, that is returned in
	 * case of 401 error.
	 * 
	 * @return value of the 'WWW-Authenticate' header
	 */
	abstract public String getAuthenticateHeaderValue();

	/**
	 * Service the given request
	 * 
	 * @param request the HTTP request
	 * @param response the server's response
	 * 
	 * @throws ServletException error inside the servlet container
	 * @throws IOException generic I/O error
	 */
	public void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		log.debug("Received WebDAV request");

		try {
			WebdavRequest webdavRequest = new WebdavRequestImpl(request, getLocatorFactory());

			// DeltaV requires 'Cache-Control' header for all methods except
			// 'VERSION-CONTROL' and 'REPORT'.
			int methodCode = DavMethods.getMethodCode(request.getMethod());

			log.debug("method {} {}", request.getMethod(), methodCode);

			boolean noCache = DavMethods.isDeltaVMethod(webdavRequest)
					&& !(DavMethods.DAV_VERSION_CONTROL == methodCode || DavMethods.DAV_REPORT == methodCode);
			WebdavResponse webdavResponse = new WebdavResponseImpl(response, noCache);

			try {
				Session session = SessionManager.get().getSession(request);
				if (session == null)
					throw new DavException(DavServletResponse.SC_FORBIDDEN);
				
				SessionManager.get().renew(session.getSid());

				DavSessionImpl davSession = new DavSessionImpl();
				davSession.setTenantId(SessionManager.get().get(session.getSid()).getTenantId());
				davSession.putObject("sid", session.getSid());
				UserDAO dao = (UserDAO) Context.get().getBean(UserDAO.class);
				User user = dao.findById(session.getUserId());
				dao.initialize(user);
				davSession.putObject("id", session.getUserId());
				davSession.putObject("user", user);

				webdavRequest.setDavSession(davSession);

				String path = webdavRequest.getRequestLocator().getResourcePath();
				if (path.startsWith("/store") == false && path.startsWith("/vstore") == false)
					throw new DavException(DavServletResponse.SC_NOT_FOUND);

				// check matching if=header for lock-token relevant operations
				DavResource resource = getResourceFactory().createResource(webdavRequest.getRequestLocator(), webdavRequest, davSession);

				if (!isPreconditionValid(webdavRequest, resource)) {
					webdavResponse.sendError(DavServletResponse.SC_PRECONDITION_FAILED);
					return;
				}
				if (!execute(webdavRequest, webdavResponse, methodCode, resource)) {
					super.service(request, response);
				}

			} catch (DavException e) {
				log.error(e.getMessage(), e);
				if (e.getErrorCode() != HttpServletResponse.SC_UNAUTHORIZED) {
					webdavResponse.sendError(e);
				}
			} catch (Throwable e) {
				if (e instanceof UnsupportedOperationException) {
					log.warn("Unsupported Operation: {} {}", request.getMethod(), methodCode);
				} else {
					log.error(e.getMessage(), e);
					throw new RuntimeException(e);
				}
			} finally {

			}

			response.getOutputStream().flush();
			response.getOutputStream().close();
		} catch (Throwable t) {
			log.error(t.getMessage(), t);
		}
	}

	/**
	 * Executes the respective method in the given webdav context
	 * 
	 * @param request the HTTP request
	 * @param response the server's response
	 * @param method the HTTP method
	 * 
	 * @param resource the DAV resource
	 * 
	 * @throws ServletException error inside the servlet container
	 * @throws IOException generic I/O error
	 * @throws DavException generic error in the WebDAV communication
	 */
	protected boolean execute(WebdavRequest request, WebdavResponse response, int method, DavResource resource)
			throws ServletException, IOException, DavException {

		switch (method) {
		case DavMethods.DAV_GET:
			doGet(request, response, resource);
			break;
		case DavMethods.DAV_HEAD:
			doHead(request, response, resource);
			break;
		case DavMethods.DAV_PROPFIND:
			doPropFind(request, response, resource);
			break;
		case DavMethods.DAV_PROPPATCH:
			doPropPatch(request, response, resource);
			break;
		case DavMethods.DAV_POST:
			doPost(request, response, resource);
			break;
		case DavMethods.DAV_PUT:
			doPut(request, response, resource);
			break;
		case DavMethods.DAV_DELETE:
			doDelete(request, response, resource);
			break;
		case DavMethods.DAV_COPY:
			doCopy(request, response, resource);
			break;
		case DavMethods.DAV_MOVE:
			doMove(request, response, resource);
			break;
		case DavMethods.DAV_MKCOL:
			doMkCol(request, response, resource);
			break;
		case DavMethods.DAV_OPTIONS:
			doOptions(request, response, resource);
			break;
		case DavMethods.DAV_LOCK:
			doLock(request, response, resource);
			break;
		case DavMethods.DAV_UNLOCK:
			doUnlock(request, response, resource);
			break;
		case DavMethods.DAV_CHECKOUT:
			doCheckout(request, response, resource);
			break;
		case DavMethods.DAV_CHECKIN:
			doCheckin(request, response, resource);
			break;
		case DavMethods.DAV_REPORT:
			doReport(request, response, resource);
			break;
		case DavMethods.DAV_VERSION_CONTROL:
			doVersionControl(request, response, resource);
			break;
		case DavMethods.DAV_UNCHECKOUT:
			doUncheckout(request, response, resource);
			break;
		default:
			// any other method
			return false;
		}
		return true;
	}

	/**
	 * The HEAD method
	 * 
	 * @param request the HTTP request
	 * @param response the server's response
	 * @param resource the DAV resource
	 * 
	 * @throws IOException generic I/O error
	 */
	protected void doHead(WebdavRequest request, WebdavResponse response, DavResource resource)
			throws IOException, DavException {
		log.debug("head");
		spoolResource(request, response, resource, false);
	}

	/**
	 * The GET method
	 * 
	 * @param request the HTTP request
	 * @param response the server's response
	 * @param resource the DAV resource
	 * 
	 * @throws IOException generic I/O error
	 */
	protected void doGet(WebdavRequest request, WebdavResponse response, DavResource resource)
			throws IOException, DavException {

		log.debug("get");

		try {
			spoolResource(request, response, resource, true);
		} catch (DavException dave) {
			log.debug(dave.getMessage(), dave);
			response.setStatus(dave.getErrorCode());
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			response.sendError(DavServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * @param request the HTTP request
	 * @param response the server's response
	 * @param resource the DAV resource
	 * @param sendContent
	 * 
	 * @throws IOException generic I/O error
	 * @throws DavException
	 */
	private void spoolResource(WebdavRequest request, WebdavResponse response, DavResource resource,
			boolean sendContent) throws IOException, DavException {

		if (!resource.exists()) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		long modSince = request.getDateHeader("If-Modified-Since");
		if (modSince > UNDEFINED_TIME) {
			long modTime = resource.getModificationTime();
			// test if resource has been modified. note that formatted
			// modification
			// time lost the milli-second precision
			if (modTime != UNDEFINED_TIME && (modTime / 1000 * 1000) <= modSince) {
				// resource has not been modified since the time indicated in
				// the
				// 'If-Modified-Since' header.
				response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
				return;
			}
		}

		OutputStream outX = (sendContent) ? response.getOutputStream() : null;
		OutputContext oc = getOutputContext(response, outX);

		if (!resource.isCollection()) {

			if (resource instanceof DavResourceImpl) {
				log.debug("resource instanceof DavResourceImpl");
				DavResourceImpl dri = (DavResourceImpl) resource;

				// Enforce download permission
				ExportContext exportCtx = dri.getExportContext(oc);
				if (!exportCtx.getResource().isDownloadEnabled()) {
					throw new DavException(DavServletResponse.SC_FORBIDDEN, "Download permission not granted to this user");
				}

				// Deals with ranges
				String rangeHeader = request.getHeader(HttpHeaders.RANGE);
				if (rangeHeader != null) {

					Pair<String, String> parsedRange = null;
					try {
						parsedRange = parseRangeRequestHeader(rangeHeader);
						log.debug("parsedRange {}", parsedRange);
					} catch (DavException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					
//					resource = new RangeResourceImpl(dri.getLocator(), dri.getFactoryLD(), dri.getSessionLD(),
//							dri.getConfig(), dri.getResource(), parsedRange);
					
					DavSession session = (com.logicaldoc.webdav.session.DavSession) request.getDavSession();
					resource = getResourceFactory().createRangeResource(dri.getLocator(), session, parsedRange);

					log.debug("Create RangeResourceImpl {}", resource);

					response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
					response.setHeader(HttpHeaders.PRAGMA, "no-cache");
				}
			}

		}

		// spool resource properties and ev. resource content.
		// OutputStream out = (sendContent) ? response.getOutputStream() : null;
		// resource.spool(getOutputContext(response, out));

		resource.spool(oc);
	}

	/**
	 * Processes the given range header field, if it is supported. Only headers
	 * containing a single byte range are supported.<br/>
	 * <code>
	 * bytes=100-200<br/>
	 * bytes=-500<br/>
	 * bytes=1000-
	 * </code>
	 * 
	 * @return Tuple of lower and upper range.
	 * @throws DavException HTTP statuscode 400 for malformed requests. 416 if
	 *         requested range is not supported.
	 */
	private Pair<String, String> parseRangeRequestHeader(String rangeHeader) throws DavException {
		assert rangeHeader != null;
		if (!rangeHeader.startsWith(RANGE_BYTE_PREFIX)) {
			throw new DavException(DavServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
		}
		final String byteRangeSet = StringUtils.removeStartIgnoreCase(rangeHeader, RANGE_BYTE_PREFIX);
		final String[] byteRanges = StringUtils.split(byteRangeSet, RANGE_SET_SEP);
		if (byteRanges.length != 1) {
			throw new DavException(DavServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
		}
		final String byteRange = byteRanges[0];
		final String[] bytePos = StringUtils.splitPreserveAllTokens(byteRange, RANGE_SEP);
		if (bytePos.length != 2 || bytePos[0].isEmpty() && bytePos[1].isEmpty()) {
			throw new DavException(DavServletResponse.SC_BAD_REQUEST, "malformed range header: " + rangeHeader);
		}
		return new ImmutablePair<>(bytePos[0], bytePos[1]);
	}

	/**
	 * The PROPFIND method
	 * 
	 * @param request the HTTP request
	 * @param response the server's response
	 * @param resource the DAV resource
	 * 
	 * @throws IOException generic I/O error
	 */
	protected void doPropFind(WebdavRequest request, WebdavResponse response, DavResource resource)
			throws IOException, DavException {

		log.debug("doPropFind");

		log.debug("[READ] FINDING {} {}",
				(resource.isCollection() ? "DOCUMENTS WITHIN THE FOLDER" : "JUST THE DOCUMENT"),
				resource.getDisplayName());

		if (!resource.exists()) {
			log.warn("Resource not found: {}", resource.getResourcePath());
			response.sendError(DavServletResponse.SC_NOT_FOUND);
			return;
		}

		DavPropertyNameSet requestProperties = request.getPropFindProperties();

		if (log.isDebugEnabled()) {
			DavPropertyNameIterator iter = requestProperties.iterator();
			StringBuffer sb = new StringBuffer("Requested properties: ");
			while (iter.hasNext()) {
				sb.append(((DavPropertyName) iter.next()).getName());
				sb.append(",");
			}
			log.debug(sb.toString());
		}

		int propfindType = request.getPropFindType();

		int depth = Context.get().getProperties().getInt("webdav.depth", 1);
		String headerDepth = request.getHeader("Depth");
		if (StringUtils.isNotEmpty(headerDepth)) {
			if ("infinity".equals(headerDepth))
				depth = request.getDepth(DEPTH_INFINITY);
			else {
				depth = Integer.parseInt(headerDepth);
			}
		}

		MultiStatus mstatus = new MultiStatus();
		mstatus.addResourceProperties(resource, requestProperties, propfindType, depth);

		response.sendMultiStatus(mstatus);
	}

	/**
	 * The PROPPATCH method
	 * 
	 * @param request the HTTP request
	 * @param response the server's response
	 * @param resource the DAV resource
	 * 
	 * @throws IOException generic I/O error
	 */
	protected void doPropPatch(WebdavRequest request, WebdavResponse response, DavResource resource)
			throws IOException, DavException {
		log.debug("doPropPatch");

		List<? extends PropEntry> changeList = request.getPropPatchChangeList();
		if (changeList.isEmpty()) {
			response.sendError(DavServletResponse.SC_BAD_REQUEST);
			return;
		}

		MultiStatus ms = new MultiStatus();
		MultiStatusResponse msr = resource.alterProperties(changeList);
		ms.addResponse(msr);
		response.sendMultiStatus(ms);
	}

	/**
	 * The POST method. Delegate to PUT
	 * 
	 * @param request the HTTP request
	 * @param response the server's response
	 * @param resource the DAV resource
	 * 
	 * @throws IOException generic I/O error
	 * @throws DavException error in the DAV communication
	 */
	protected void doPost(WebdavRequest request, WebdavResponse response, DavResource resource)
			throws IOException, DavException {
		log.debug("************doPost*****************");
		doPut(request, response, resource);
	}

	/**
	 * The PUT method
	 * 
	 * @param request the HTTP request
	 * @param response the server's response
	 * @param resource the DAV resource
	 * 
	 * @throws IOException generic I/O error
	 * @throws DavException error in the DAV communication
	 */
	protected void doPut(WebdavRequest request, WebdavResponse response, DavResource resource)
			throws IOException, DavException {

		log.debug("************doPut*****************");

		log.debug("[ADD] Document {}", resource.getDisplayName());

		try {
			DavResource parentResource = resource.getCollection();

			if (parentResource == null || !parentResource.exists()) {
				// parent does not exist
				response.sendError(DavServletResponse.SC_CONFLICT);
				return;
			}

			int status;
			// test if resource already exists
			if (resource.exists()) {
				status = DavServletResponse.SC_NO_CONTENT;
			} else {
				status = DavServletResponse.SC_CREATED;
			}

//			boolean isChunkedUpload = false;
//			if (request.getHeader("LD-Chunked") != null) {
//				isChunkedUpload = true;
//			}

			parentResource.addMember(resource, getInputContext(request, request.getInputStream()));

//			if (!isChunkedUpload) {
//				WebdavRequest webdavRequest = new WebdavRequestImpl(request, getLocatorFactory());
//				webdavRequest.setDavSession(request.getDavSession());
//				DavSession session = (com.logicaldoc.webdav.session.DavSession) webdavRequest.getDavSession();
//			} 

			response.setStatus(status);
		} catch (DavException dave) {

			log.debug("A DavException occurred!", dave);

			// return the status code
			response.setStatus(dave.getErrorCode());

		} catch (Exception e) {
			log.error("An Exception occurred!", e);
			response.sendError(DavServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * The MKCOL method
	 * 
	 * @param request the HTTP request
	 * @param response the server's response
	 * @param resource the DAV resource
	 * 
	 * @throws IOException generic I/O error
	 * @throws DavException error in the DAV communication
	 */
	protected void doMkCol(WebdavRequest request, WebdavResponse response, DavResource resource)
			throws IOException, DavException {

		log.debug("doMkCol");
		log.debug("[ADD] Directory {}", resource.getDisplayName());

		try {
			DavResource parentResource = resource.getCollection();
			if (parentResource == null || !parentResource.exists() || !parentResource.isCollection()) {
				// parent does not exist or is not a collection
				response.sendError(DavServletResponse.SC_CONFLICT);
				return;
			}
			// shortcut: mkcol is only allowed on deleted/non-existing resources
			if (resource.exists()) {
				response.sendError(DavServletResponse.SC_METHOD_NOT_ALLOWED);
			}

			if (request.getContentLength() > 0 || request.getHeader("Transfer-Encoding") != null) {
				parentResource.addMember(resource, getInputContext(request, request.getInputStream()));
			} else {
				parentResource.addMember(resource, getInputContext(request, null));
			}
			response.setStatus(DavServletResponse.SC_CREATED);
		} catch (DavException dave) {
			log.debug("Error during folder creation", dave);
			response.setStatus(dave.getErrorCode());
		} catch (Exception e) {
			log.error("Error during folder creation", e);
			response.sendError(DavServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * The DELETE method
	 * 
	 * @param request the HTTP request
	 * @param response the server's response
	 * @param resource the DAV resource
	 * 
	 * @throws IOException generic I/O error
	 * @throws DavException error in the DAV communication
	 */
	protected void doDelete(WebdavRequest request, WebdavResponse response, DavResource resource)
			throws IOException, DavException {

		log.debug("doDelete");
		try {
			log.debug("[DELETE] {} {}", (resource.isCollection() ? " FOLDER" : " DOCUMENT"), resource.getDisplayName());

			DavResource parent = resource.getCollection();
			if (parent != null) {
				parent.removeMember(resource);
				response.setStatus(DavServletResponse.SC_NO_CONTENT);
			} else {
				throw new DavException(DavServletResponse.SC_FORBIDDEN, "Cannot remove the root resource.");
			}
		} catch (DavException dave) {
			log.debug(dave.getMessage(), dave);
			response.setStatus(dave.getErrorCode());
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			response.sendError(DavServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * The COPY method
	 * 
	 * @param request the HTTP request
	 * @param response the server's response
	 * @param resource the DAV resource
	 * 
	 * @throws IOException generic I/O error
	 * @throws DavException error in the DAV communication
	 */
	protected void doCopy(WebdavRequest request, WebdavResponse response, DavResource resource)
			throws IOException, DavException {
		
		log.debug("doCopy");
		// only depth 0 and infinity is allowed
		int depth = request.getDepth(DEPTH_INFINITY);
		if (!(depth == DEPTH_0 || depth == DEPTH_INFINITY)) {
			response.sendError(DavServletResponse.SC_BAD_REQUEST);
			return;
		}

		DavResource destResource = null;
		try {
			destResource = getResourceFactory().createResource(request.getDestinationLocator(), request);
		} catch (Throwable e) {
			destResource = resource.getCollection();
		}
		
		int status = validateDestination(destResource, request);
		if (status > DavServletResponse.SC_NO_CONTENT) {
			response.sendError(status);
			return;
		}

		resource.copy(destResource, depth == DEPTH_0);
		response.setStatus(status);
	}

	/**
	 * The MOVE method
	 * 
	 * @param request the HTTP request
	 * @param response the server's response
	 * @param resource the DAV resource
	 * 
	 * @throws IOException generic I/O error
	 * @throws DavException error in the DAV communication
	 */
	protected void doMove(WebdavRequest request, WebdavResponse response, DavResource resource)
			throws IOException, DavException {

		log.debug("doMove");
		
		WebdavRequest webdavRequest = new WebdavRequestImpl(request, getLocatorFactory());
		webdavRequest.setDavSession(request.getDavSession());
		DavSession session = (com.logicaldoc.webdav.session.DavSession) webdavRequest.getDavSession();
		try {
			DavResource destResource = null;
			try {
				destResource = getResourceFactory().createResource(request.getDestinationLocator(), request, session);
			} catch (Throwable e) {
				destResource = resource.getCollection();
			}

			int status = validateDestination(destResource, request);
			log.debug("status = " + status);
			
			if (status > DavServletResponse.SC_NO_CONTENT) {
				log.debug("status > DavServletResponse.SC_NO_CONTENT");
				response.sendError(status);
				return;
			}

			resource.move(destResource);
			response.setStatus(status);
		} catch (DavException dave) {
			log.debug("Exception during move", dave);
			response.setStatus(dave.getErrorCode());
		} catch (Exception e) {
			log.error("Error during move", e);
			response.sendError(DavServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Validate the given destination resource and return the proper status
	 * code: Any return value greater/equal than
	 * {@link DavServletResponse#SC_NO_CONTENT} indicates an error.
	 * 
	 * @param destResource destination resource to be validated.
	 * @param request the HTTP request
	 * 
	 * @return status code indicating whether the destination is valid.
	 */
	private int validateDestination(DavResource destResource, WebdavRequest request) throws DavException {

		String destHeader = request.getHeader(HEADER_DESTINATION);
		if (destHeader == null || "".equals(destHeader)) {
			return DavServletResponse.SC_BAD_REQUEST;
		}
		if (destResource.getLocator().equals(request.getRequestLocator())) {
			return DavServletResponse.SC_FORBIDDEN;
		}

		int status;
		if (destResource.exists()) {
			if (request.isOverwrite()) {
				// matching if-header required for existing resources
				if (!request.matchesIfHeader(destResource)) {
					return DavServletResponse.SC_PRECONDITION_FAILED;
				} else {
					// overwrite existing resource
					destResource.getCollection().removeMember(destResource);
					status = DavServletResponse.SC_NO_CONTENT;
				}
			} else {
				// cannot copy/move to an existing item, if overwrite is not
				// forced
				return DavServletResponse.SC_PRECONDITION_FAILED;
			}
		} else {
			// destination does not exist >> copy/move can be performed
			status = DavServletResponse.SC_CREATED;
		}
		return status;
	}

	/**
	 * The OPTION method
	 * 
	 * @param request the HTTP request
	 * @param response the server's response
	 * @param resource the DAV resource
	 */
	protected void doOptions(WebdavRequest request, WebdavResponse response, DavResource resource)
			throws IOException, DavException {
		log.debug("doOptions");

		response.addHeader(DavConstants.HEADER_DAV, resource.getComplianceClass());
		response.addHeader("Allow", resource.getSupportedMethods());
		response.addHeader("MS-Author-Via", DavConstants.HEADER_DAV);
		if (resource instanceof SearchResource) {
			String[] langs = ((SearchResource) resource).getQueryGrammerSet().getQueryLanguages();
			for (int i = 0; i < langs.length; i++) {
				response.addHeader(SearchConstants.HEADER_DASL, "<" + langs[i] + ">");
			}
		}
		// with DeltaV the OPTIONS request may contain a Xml body.
		OptionsResponse oR = null;
		OptionsInfo oInfo = request.getOptionsInfo();
		if (oInfo != null && resource instanceof DeltaVResource) {
			oR = ((DeltaVResource) resource).getOptionResponse(oInfo);
		}
		if (oR == null) {
			response.setStatus(DavServletResponse.SC_OK);
		} else {
			response.sendXmlResponse(oR, DavServletResponse.SC_OK);
		}
	}

	protected void doVersionControl(WebdavRequest request, WebdavResponse response, DavResource resource)
			throws DavException, IOException {
		log.debug("doVersionControl");
		if (!(resource instanceof VersionableResource)) {
			response.sendError(DavServletResponse.SC_METHOD_NOT_ALLOWED);
			return;
		}
		((VersionableResource) resource).addVersionControl();
	}

	protected void doLock(WebdavRequest request, WebdavResponse response, DavResource resource)
			throws IOException, DavException {
		log.debug("doLock - interpreting as checkout");
		doCheckout(request, response, resource);
	}

	/**
	 * The UNLOCK method
	 * 
	 * @param request the HTTP request
	 * @param response the server's response
	 * @param resource the DAV resource
	 * 
	 * @throws IOException generic I/O error
	 * @throws DavException error in the DAV communication
	 */
	protected void doUnlock(WebdavRequest request, WebdavResponse response, DavResource resource)
			throws DavException, IOException {
		doUncheckout(request, response, resource);
	}

	/**
	 * The EVENT_CHECKEDOUT method
	 * 
	 * @param request the HTTP request
	 * @param response the server's response
	 * @param resource the DAV resource
	 * 
	 * @throws DavException
	 * @throws IOException
	 */
	protected void doCheckout(WebdavRequest request, WebdavResponse response, DavResource resource)
			throws DavException, IOException {
		log.debug("doCheckout");
		if (!(resource instanceof VersionControlledResource)) {
			response.sendError(DavServletResponse.SC_METHOD_NOT_ALLOWED);
			return;
		}
		((VersionControlledResource) resource).checkout();
	}

	/**
	 * The EVENT_CHECKEDIN method
	 * 
	 * @param request the HTTP request
	 * @param response the server's response
	 * @param resource the DAV resource
	 * 
	 * @throws IOException generic I/O error
	 * @throws DavException error in the DAV communication
	 */
	protected void doCheckin(WebdavRequest request, WebdavResponse response, DavResource resource)
			throws DavException, IOException {
		log.debug("doCheckin");

		response.sendError(DavServletResponse.SC_METHOD_NOT_ALLOWED);
	}

	/**
	 * The REPORT method
	 * 
	 * @param request the HTTP request
	 * @param response the server's response
	 * @param resource the DAV resource
	 * 
	 * @throws IOException generic I/O error
	 * @throws DavException error in the DAV communication
	 */
	protected void doReport(WebdavRequest request, WebdavResponse response, DavResource resource)
			throws DavException, IOException {
		log.debug("doReport");

		ReportInfo info = request.getReportInfo();
		Report report;
		if (resource instanceof DeltaVResource) {
			report = ((DeltaVResource) resource).getReport(info);
		} else if (resource instanceof AclResource) {
			report = ((AclResource) resource).getReport(info);
		} else {
			response.sendError(DavServletResponse.SC_METHOD_NOT_ALLOWED);
			return;
		}

		int statusCode = (report.isMultiStatusReport()) ? DavServletResponse.SC_MULTI_STATUS : DavServletResponse.SC_OK;
		response.sendXmlResponse(report, statusCode);
	}

	/**
	 * The EVENT_UNCHECKOUT method
	 * 
	 * @param request the HTTP request
	 * @param response the server's response
	 * @param resource the DAV resource
	 * 
	 * @throws IOException generic I/O error
	 * @throws DavException error in the DAV communication
	 */
	protected void doUncheckout(WebdavRequest request, WebdavResponse response, DavResource resource)
			throws DavException, IOException {
		log.debug("doUncheckout(" + resource.getDisplayName() + ")");
		if (!(resource instanceof VersionControlledResource)) {
			response.sendError(DavServletResponse.SC_METHOD_NOT_ALLOWED);
			return;
		}
		((VersionControlledResource) resource).uncheckout();
		response.setStatus(DavServletResponse.SC_OK);
	}

	/**
	 * Return a new <code>InputContext</code> used for adding resource members
	 * 
	 * @param request the DAV request
	 * @param in the stream of the request
	 * 
	 * @return the input context
	 * @see #spoolResource(WebdavRequest, WebdavResponse, DavResource, boolean)
	 */
	protected InputContext getInputContext(DavServletRequest request, InputStream in) {
		return new InputContextImpl(request, in);
	}

	/**
	 * Return a new <code>OutputContext</code> used for spooling resource
	 * properties and the resource content
	 * 
	 * @param response the DAV response
	 * @param out stream of the output
	 * 
	 * @return the output context
	 * 
	 * @see #doPut(WebdavRequest, WebdavResponse, DavResource)
	 * @see #doPost(WebdavRequest, WebdavResponse, DavResource)
	 * @see #doMkCol(WebdavRequest, WebdavResponse, DavResource)
	 */
	protected OutputContext getOutputContext(DavServletResponse response, OutputStream out) {
		return new OutputContextImpl(response, out);
	}

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		context = config.getServletContext();
	}

	public static ServletContext getContext() {
		return context;
	}
}