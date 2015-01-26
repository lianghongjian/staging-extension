package org.exoplatform.management.common;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.ImportUUIDBehavior;
import javax.jcr.LoginException;
import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.nodetype.NodeDefinition;
import javax.jcr.nodetype.NodeType;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;

import org.apache.commons.lang.ArrayUtils;
import org.exoplatform.commons.utils.ActivityTypeUtils;
import org.exoplatform.services.cms.templates.TemplateService;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.jcr.core.ManageableRepository;
import org.exoplatform.services.jcr.ext.common.SessionProvider;
import org.exoplatform.services.jcr.impl.core.SessionImpl;
import org.gatein.management.api.operation.model.ExportTask;

public abstract class AbstractJCROperationHandler extends AbstractOperationHandler {

  protected RepositoryService repositoryService = null;
  protected TemplateService templateService = null;

  private Map<String, Boolean> isNTRecursiveMap = new HashMap<String, Boolean>();

  protected final void exportNode(Node childNode, List<ExportTask> subNodesExportTask, String... params) throws Exception {
    String path = childNode.getPath();
    boolean recursive = isRecursiveExport(childNode);
    addJCRNodeExportTask(childNode, subNodesExportTask, recursive, ((String[]) ArrayUtils.add(params, path)));

    // If not export the whole node
    if (!recursive) {
      NodeIterator nodeIterator = childNode.getNodes();
      while (nodeIterator.hasNext()) {
        Node node = nodeIterator.nextNode();
        exportNode(node, subNodesExportTask, params);
      }
    }
  }

  protected final void importNode(FileEntry fileEntry, String workspace, boolean isCleanPublication) throws Exception {
    File xmlFile = fileEntry.getFile();
    if (xmlFile == null || !xmlFile.exists()) {
      log.warn("Cannot import file" + xmlFile);
      return;
    }
    FileInputStream fis = new FileInputStream(xmlFile);
    try {
      importNode(fileEntry.getNodePath(), workspace, fis, fileEntry.getHistoryFile(), isCleanPublication);
    } finally {
      if (fis != null) {
        fis.close();
      }
      if (xmlFile != null) {
        xmlFile.delete();
      }
    }
  }

  protected final void importNode(String nodePath, String workspace, InputStream inputStream, File historyFile, boolean isCleanPublication) throws Exception {
    String parentNodePath = nodePath.substring(0, nodePath.lastIndexOf("/"));
    parentNodePath = parentNodePath.replaceAll("//", "/");

    // Delete old node
    Session session = getSession(workspace);
    try {
      if (session.itemExists(nodePath) && session.getItem(nodePath) instanceof Node) {
        log.info("Deleting the node " + workspace + ":" + nodePath);

        Node oldNode = (Node) session.getItem(nodePath);
        if (oldNode.isNodeType("exo:activityInfo") && activityManager != null) {
          String activityId = ActivityTypeUtils.getActivityId(oldNode);
          deleteActivity(activityId);
        }

        oldNode.remove();
        session.save();
        session.refresh(false);
      }
    } catch (Exception e) {
      log.error("Error when trying to find and delete the node: '" + nodePath + "'. Ignore this node and continue.", e);
      return;
    } finally {
      if (session != null) {
        session.logout();
      }
    }

    // Import Node from Extracted Zip file
    session = getSession(workspace);
    FileInputStream historyFis1 = null, historyFis2 = null;
    try {
      log.info("Importing the node '" + nodePath + "'");

      // Create the parent path
      Node currentNode = createJCRPath(session, parentNodePath);

      session.refresh(false);
      session.importXML(parentNodePath, inputStream, ImportUUIDBehavior.IMPORT_UUID_CREATE_NEW);
      session.save();

      if (isCleanPublication) {
        // Clean publication information
        cleanPublication(parentNodePath, session);
      } else if (historyFile != null) {
        log.info("Importing history of the node " + nodePath);
        historyFis1 = new FileInputStream(historyFile);
        Map<String, String> mapHistoryValue = org.exoplatform.services.cms.impl.Utils.getMapImportHistory(historyFis1);

        historyFis2 = new FileInputStream(historyFile);
        org.exoplatform.services.cms.impl.Utils.processImportHistory(currentNode, historyFis2, mapHistoryValue);
      }
    } catch (Exception e) {
      log.error("Error when trying to import node: " + nodePath, e);
      // Revert changes
      session.refresh(false);
    } finally {
      if (session != null) {
        session.logout();
      }
      if (historyFis1 != null) {
        historyFis1.close();
      }
      if (historyFis2 != null) {
        historyFis2.close();
      }
    }
  }

  protected final boolean isRecursiveExport(Node node) throws Exception {
    NodeType nodeType = node.getPrimaryNodeType();
    NodeType[] nodeTypes = node.getMixinNodeTypes();
    boolean recursive = isRecursiveNT(nodeType);
    if (!recursive && nodeTypes != null && nodeTypes.length > 0) {
      int i = 0;
      while (!recursive && i < nodeTypes.length) {
        recursive = isRecursiveNT(nodeTypes[i]);
        i++;
      }
    }
    return recursive;
  }

  protected final boolean isRecursiveNT(NodeType nodeType) throws Exception {
    if (!isNTRecursiveMap.containsKey(nodeType.getName())) {
      boolean hasMandatoryChild = false;
      NodeDefinition[] nodeDefinitions = nodeType.getChildNodeDefinitions();
      if (nodeDefinitions != null) {
        int i = 0;
        while (!hasMandatoryChild && i < nodeDefinitions.length) {
          hasMandatoryChild = nodeDefinitions[i].isMandatory();
          i++;
        }
      }
      boolean recursive = hasMandatoryChild;
      if (templateService != null) {
        recursive |= templateService.isManagedNodeType(nodeType.getName());
      }
      isNTRecursiveMap.put(nodeType.getName(), recursive);
    }
    return isNTRecursiveMap.get(nodeType.getName());
  }

  protected final Session getSession(String workspace) throws RepositoryException, LoginException, NoSuchWorkspaceException {
    return getSession(repositoryService, workspace);
  }

  public static final Session getSession(RepositoryService repositoryService, String workspace) throws RepositoryException, LoginException, NoSuchWorkspaceException {
    SessionProvider provider = SessionProvider.createSystemProvider();
    ManageableRepository repository = repositoryService.getCurrentRepository();
    Session session = provider.getSession(workspace, repository);
    if (session instanceof SessionImpl) {
      ((SessionImpl) session).setTimeout(ONE_DAY_IN_MS);
    }
    return session;
  }

  protected final void cleanPublication(String path, Session session) throws Exception {
    QueryManager manager = session.getWorkspace().getQueryManager();
    String statement = "select * from nt:base where jcr:path LIKE '" + path + "/%' and publication:liveRevision IS NOT NULL";
    Query query = manager.createQuery(statement.toString(), Query.SQL);
    NodeIterator iter = query.execute().getNodes();
    while (iter.hasNext()) {
      Node node = iter.nextNode();
      cleanPublication(node);
    }
    if (session.itemExists(path)) {
      Node node = (Node) session.getItem(path);
      cleanPublication(node);
    }
  }

  protected final void cleanPublication(Node node) throws Exception {
    if (node.hasProperty("publication:currentState")) {
      log.info("\"" + node.getName() + "\" publication lifecycle has been cleaned up");
      // See in case the content is enrolled for the first time but never
      // publisher in "source server", if yes, set manually "published" state
      Value[] values = node.getProperty("publication:revisionData").getValues();
      if (values.length < 2) {
        String user = node.getProperty("publication:lastUser").getString();
        node.setProperty("publication:revisionData", new String[] { node.getUUID() + ",published," + user });
      }
      node.setProperty("publication:liveRevision", "");
      node.setProperty("publication:currentState", "published");
      node.save();
    }
  }

  protected final Node createJCRPath(Session session, String path) throws RepositoryException {
    String[] ancestors = path.split("/");
    Node current = session.getRootNode();
    for (int i = 0; i < ancestors.length; i++) {
      if (!"".equals(ancestors[i])) {
        if (current.hasNode(ancestors[i])) {
          current = current.getNode(ancestors[i]);
        } else {
          if (log.isInfoEnabled()) {
            log.info("Creating folder: " + ancestors[i] + " in node : " + current.getPath());
          }
          current = current.addNode(ancestors[i], "nt:unstructured");
          session.save();
        }
      }
    }
    return current;
  }

  protected void addJCRNodeExportTask(Node childNode, List<ExportTask> subNodesExportTask, boolean recursive, String... params) throws Exception {}
}
