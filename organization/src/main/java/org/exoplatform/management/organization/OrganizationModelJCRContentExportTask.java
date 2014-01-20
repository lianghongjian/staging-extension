package org.exoplatform.management.organization;

import java.io.IOException;
import java.io.OutputStream;

import javax.jcr.Node;
import javax.jcr.Session;

import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.jcr.core.ManageableRepository;
import org.exoplatform.services.jcr.ext.common.SessionProvider;
import org.exoplatform.services.organization.Group;
import org.exoplatform.services.organization.User;
import org.gatein.management.api.exceptions.OperationException;
import org.gatein.management.api.operation.OperationNames;
import org.gatein.management.api.operation.model.ExportTask;

/**
 * @author <a href="mailto:bkhanfir@exoplatform.com">Boubaker Khanfir</a>
 * @version $Revision$
 */
public class OrganizationModelJCRContentExportTask implements ExportTask {
  public static final String GROUPS_PATH = "groupsPath";

  private final RepositoryService repositoryService;
  private final String serializationPath;
  private final String jcrPath;
  private final String workspace;

  public OrganizationModelJCRContentExportTask(RepositoryService repositoryService, Node node, Object organizationObject) throws Exception {
    this.repositoryService = repositoryService;
    this.jcrPath = node.getPath();
    this.workspace = node.getSession().getWorkspace().getName();
    if (organizationObject instanceof User) {
      serializationPath = "users/" + ((User) organizationObject).getUserName() + "/u_content.xml";
    } else if (organizationObject instanceof Group) {
      serializationPath = "groups" + ((Group) organizationObject).getId() + "/g_content.xml";
    } else {
      serializationPath = null;
    }
  }

  @Override
  public String getEntry() {
    return serializationPath;
  }

  @Override
  public void export(OutputStream outputStream) throws IOException {
    SessionProvider sessionProvider = SessionProvider.createSystemProvider();
    try {
      ManageableRepository manageableRepository = repositoryService.getCurrentRepository();
      Session session = sessionProvider.getSession(workspace, manageableRepository);
      session.exportSystemView(jcrPath, outputStream, false, false);
      outputStream.flush();
    } catch (Exception exception) {
      throw new OperationException(OperationNames.EXPORT_RESOURCE, "Error while exporting user's personnal JCR contents", exception);
    }
  }
}