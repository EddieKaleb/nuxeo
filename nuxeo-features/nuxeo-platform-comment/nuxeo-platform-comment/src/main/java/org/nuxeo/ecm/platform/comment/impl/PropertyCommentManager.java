/*
 * (C) Copyright 2018 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Funsho David
 *     Nuno Cunha <ncunha@nuxeo.com>
 */

package org.nuxeo.ecm.platform.comment.impl;

import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toList;
import static org.nuxeo.ecm.platform.comment.api.ExternalEntityConstants.EXTERNAL_ENTITY_FACET;
import static org.nuxeo.ecm.platform.comment.workflow.utils.CommentsConstants.COMMENT_ANCESTOR_IDS;
import static org.nuxeo.ecm.platform.comment.workflow.utils.CommentsConstants.COMMENT_AUTHOR;
import static org.nuxeo.ecm.platform.comment.workflow.utils.CommentsConstants.COMMENT_CREATION_DATE;
import static org.nuxeo.ecm.platform.comment.workflow.utils.CommentsConstants.COMMENT_DOC_TYPE;
import static org.nuxeo.ecm.platform.comment.workflow.utils.CommentsConstants.COMMENT_PARENT_ID;
import static org.nuxeo.ecm.platform.comment.workflow.utils.CommentsConstants.COMMENT_SCHEMA;
import static org.nuxeo.ecm.platform.query.nxql.CoreQueryAndFetchPageProvider.CORE_SESSION_PROPERTY;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.core.api.CloseableCoreSession;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.api.PartialList;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.core.api.SortInfo;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.platform.comment.api.Comment;
import org.nuxeo.ecm.platform.comment.api.CommentEvents;
import org.nuxeo.ecm.platform.comment.api.Comments;
import org.nuxeo.ecm.platform.comment.api.ExternalEntity;
import org.nuxeo.ecm.platform.comment.api.exceptions.CommentNotFoundException;
import org.nuxeo.ecm.platform.comment.api.exceptions.CommentSecurityException;
import org.nuxeo.ecm.platform.query.api.PageProvider;
import org.nuxeo.ecm.platform.query.api.PageProviderService;
import org.nuxeo.runtime.api.Framework;

/**
 * Comment service implementation. The comments are linked together thanks to a parent document id property.
 *
 * @since 10.3
 */
public class PropertyCommentManager extends AbstractCommentManager {

    private static final Log log = LogFactory.getLog(PropertyCommentManager.class);

    protected static final String GET_COMMENT_PAGEPROVIDER_NAME = "GET_COMMENT_AS_EXTERNAL_ENTITY";

    protected static final String GET_COMMENTS_FOR_DOC_PAGEPROVIDER_NAME = "GET_COMMENTS_FOR_DOCUMENT";

    protected static final String HIDDEN_FOLDER_TYPE = "HiddenFolder";

    protected static final String COMMENT_NAME = "comment";

    @Override
    @SuppressWarnings("unchecked")
    public List<DocumentModel> getComments(CoreSession session, DocumentModel docModel)
            throws CommentSecurityException {

        DocumentRef docRef = getAncestorRef(session, docModel);

        if (session.exists(docRef) && !session.hasPermission(docRef, SecurityConstants.READ)) {
            throw new CommentSecurityException("The user " + session.getPrincipal().getName()
                    + " does not have access to the comments of document " + docModel.getId());
        }
        PageProviderService ppService = Framework.getService(PageProviderService.class);
        return CoreInstance.doPrivileged(session, s -> {
            Map<String, Serializable> props = Collections.singletonMap(CORE_SESSION_PROPERTY, (Serializable) s);
            PageProvider<DocumentModel> pageProvider = (PageProvider<DocumentModel>) ppService.getPageProvider(
                    GET_COMMENTS_FOR_DOC_PAGEPROVIDER_NAME, singletonList(new SortInfo(COMMENT_CREATION_DATE, true)),
                    null, null, props, docModel.getId());
            return pageProvider.getCurrentPage();
        });
    }

    @Override
    public List<DocumentModel> getComments(DocumentModel docModel, DocumentModel parent) {
        throw new UnsupportedOperationException("This service implementation does not implement deprecated API.");
    }

    @Override
    public DocumentModel createComment(DocumentModel docModel, String comment) {
        throw new UnsupportedOperationException("This service implementation does not implement deprecated API.");
    }

    @Override
    public DocumentModel createComment(DocumentModel docModel, String text, String author) {
        throw new UnsupportedOperationException("This service implementation does not implement deprecated API.");
    }

    @Override
    public DocumentModel createComment(DocumentModel docModel, DocumentModel commentModel)
            throws CommentSecurityException {

        NuxeoPrincipal principal = commentModel.getCoreSession().getPrincipal();
        // Open a session as system user since the parent document model can be a comment
        try (CloseableCoreSession session = CoreInstance.openCoreSessionSystem(docModel.getRepositoryName())) {
            DocumentRef docRef = getAncestorRef(session, docModel);
            if (!session.hasPermission(principal, docRef, SecurityConstants.READ)) {
                throw new CommentSecurityException(
                        "The user " + principal.getName() + " can not create comments on document " + docModel.getId());
            }

            String path = getCommentContainerPath(session, docModel.getId());

            DocumentModel commentModelToCreate = session.createDocumentModel(path, COMMENT_NAME,
                    commentModel.getType());
            commentModelToCreate.copyContent(commentModel);
            commentModelToCreate.setPropertyValue(COMMENT_ANCESTOR_IDS,
                    (Serializable) computeAncestorIds(session, docModel.getId()));
            DocumentModel comment = session.createDocument(commentModelToCreate);
            comment.detach(true);
            notifyEvent(session, CommentEvents.COMMENT_ADDED, docModel, comment);
            return comment;
        }
    }

    @Override
    public DocumentModel createComment(DocumentModel docModel, DocumentModel parent, DocumentModel child) {
        throw new UnsupportedOperationException("This service implementation does not implement deprecated API.");
    }

    @Override
    public void deleteComment(DocumentModel docModel, DocumentModel comment) {
        throw new UnsupportedOperationException("This service implementation does not implement deprecated API.");
    }

    @Override
    public List<DocumentModel> getDocumentsForComment(DocumentModel comment) {
        throw new UnsupportedOperationException("This service implementation does not implement deprecated API.");
    }

    @Override
    public DocumentModel getThreadForComment(DocumentModel comment) throws CommentSecurityException {
        return getThreadForComment(comment.getCoreSession(), comment);
    }

    @Override
    public DocumentModel createLocatedComment(DocumentModel docModel, DocumentModel comment, String path) {
        CoreSession session = docModel.getCoreSession();
        DocumentRef docRef = getAncestorRef(session, docModel);
        if (!session.hasPermission(docRef, SecurityConstants.READ)) {
            throw new CommentSecurityException("The user " + session.getPrincipal().getName()
                    + " can not create comments on document " + docModel.getId());
        }
        return CoreInstance.doPrivileged(session, s -> {
            DocumentModel commentModel = s.createDocumentModel(path, COMMENT_NAME, comment.getType());
            commentModel.copyContent(comment);
            commentModel.setPropertyValue(COMMENT_ANCESTOR_IDS, (Serializable) computeAncestorIds(s, docModel.getId()));
            commentModel = s.createDocument(commentModel);
            notifyEvent(s, CommentEvents.COMMENT_ADDED, docModel, commentModel);
            return commentModel;
        });
    }

    @Override
    public Comment createComment(CoreSession session, Comment comment)
            throws CommentNotFoundException, CommentSecurityException {
        String parentId = comment.getParentId();
        DocumentRef docRef = new IdRef(parentId);
        // Parent document can be a comment, check existence as a privileged user
        if (!CoreInstance.doPrivileged(session, s -> {return s.exists(docRef);})) {
            throw new CommentNotFoundException("The document or comment " + comment.getParentId() + " does not exist.");
        }
        DocumentRef ancestorRef = CoreInstance.doPrivileged(session, s -> {
            return getAncestorRef(s, s.getDocument(new IdRef(parentId)));
        });
        if (!session.hasPermission(ancestorRef, SecurityConstants.READ)) {
            throw new CommentSecurityException("The user " + session.getPrincipal().getName()
                    + " can not create comments on document " + parentId);
        }

        // Initiate Creation Date if it is not done yet
        if (comment.getCreationDate() == null) {
            comment.setCreationDate(Instant.now());
        }

        return CoreInstance.doPrivileged(session, s -> {
            String path = getCommentContainerPath(s, parentId);
            DocumentModel commentModel = s.createDocumentModel(path, COMMENT_NAME, COMMENT_DOC_TYPE);
            Comments.commentToDocumentModel(comment, commentModel);
            if (comment instanceof ExternalEntity) {
                commentModel.addFacet(EXTERNAL_ENTITY_FACET);
                Comments.externalEntityToDocumentModel((ExternalEntity) comment, commentModel);
            }

            // Compute the list of ancestor ids
            commentModel.setPropertyValue(COMMENT_ANCESTOR_IDS, (Serializable) computeAncestorIds(s, parentId));
            commentModel = s.createDocument(commentModel);
            notifyEvent(s, CommentEvents.COMMENT_ADDED, s.getDocument(docRef), commentModel);
            return Comments.newComment(commentModel);
        });
    }

    @Override
    public Comment getComment(CoreSession session, String commentId)
            throws CommentNotFoundException, CommentSecurityException {
        DocumentRef commentRef = new IdRef(commentId);
        // Parent document can be a comment, check existence as a privileged user
        if (!CoreInstance.doPrivileged(session, s -> {return s.exists(commentRef);})) {
            throw new CommentNotFoundException("The comment " + commentId + " does not exist.");
        }
        NuxeoPrincipal principal = session.getPrincipal();
        return CoreInstance.doPrivileged(session, s -> {
            DocumentModel commentModel = s.getDocument(commentRef);
            DocumentRef documentRef = getAncestorRef(s, commentModel);
            if (!s.hasPermission(principal, documentRef, SecurityConstants.READ)) {
                throw new CommentSecurityException("The user " + principal.getName()
                        + " does not have access to the comments of document " + documentRef.reference());
            }

            return Comments.newComment(commentModel);
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public PartialList<Comment> getComments(CoreSession session, String documentId, Long pageSize,
            Long currentPageIndex, boolean sortAscending) throws CommentSecurityException {
        DocumentRef docRef = new IdRef(documentId);
        PageProviderService ppService = Framework.getService(PageProviderService.class);
        NuxeoPrincipal principal = session.getPrincipal();
        return CoreInstance.doPrivileged(session, s -> {
            if (s.exists(docRef)) {
                DocumentRef ancestorRef = getAncestorRef(s, s.getDocument(docRef));
                if (s.exists(ancestorRef) && !s.hasPermission(principal, ancestorRef, SecurityConstants.READ)) {
                    throw new CommentSecurityException("The user " + principal.getName()
                            + " does not have access to the comments of document " + documentId);
                }
            }
            Map<String, Serializable> props = Collections.singletonMap(CORE_SESSION_PROPERTY, (Serializable) s);
            PageProvider<DocumentModel> pageProvider = (PageProvider<DocumentModel>) ppService.getPageProvider(
                    GET_COMMENTS_FOR_DOC_PAGEPROVIDER_NAME,
                    singletonList(new SortInfo(COMMENT_CREATION_DATE, sortAscending)), pageSize, currentPageIndex,
                    props, documentId);
            List<DocumentModel> commentList = pageProvider.getCurrentPage();
            return commentList.stream()
                              .map(Comments::newComment)
                              .collect(collectingAndThen(toList(),
                                      list -> new PartialList<>(list, pageProvider.getResultsCount())));
        });
    }

    @Override
    public Comment updateComment(CoreSession session, String commentId, Comment comment)
            throws CommentNotFoundException {
        IdRef commentRef = new IdRef(commentId);
        if (!CoreInstance.doPrivileged(session, s -> {return s.exists(commentRef);})) {
            throw new CommentNotFoundException("The comment " + commentId + " does not exist.");
        }
        NuxeoPrincipal principal = session.getPrincipal();
        if (!principal.isAdministrator() && !comment.getAuthor().equals(principal.getName())) {
            throw new CommentSecurityException(
                    "The user " + principal.getName() + " can not edit comments of document " + comment.getParentId());
        }
        return CoreInstance.doPrivileged(session, s -> {
            // Initiate Modification Date if it is not done yet
            if (comment.getModificationDate() == null) {
                comment.setModificationDate(Instant.now());
            }

            DocumentModel commentModel = s.getDocument(commentRef);
            Comments.commentToDocumentModel(comment, commentModel);
            if (comment instanceof ExternalEntity) {
                Comments.externalEntityToDocumentModel((ExternalEntity) comment, commentModel);
            }
            s.saveDocument(commentModel);
            return Comments.newComment(commentModel);
        });
    }

    @Override
    public void deleteComment(CoreSession session, String commentId)
            throws CommentNotFoundException, CommentSecurityException {
        IdRef commentRef = new IdRef(commentId);
        // Document can be a comment, check existence as a privileged user
        if (!CoreInstance.doPrivileged(session, s -> {return s.exists(commentRef);})) {
            throw new CommentNotFoundException("The comment " + commentId + " does not exist.");
        }

        NuxeoPrincipal principal = session.getPrincipal();
        CoreInstance.doPrivileged(session, s -> {
            DocumentModel comment = s.getDocument(commentRef);
            String parentId = (String) comment.getPropertyValue(COMMENT_PARENT_ID);
            DocumentRef parentRef = new IdRef(parentId);
            DocumentRef ancestorRef = getAncestorRef(s, comment);
            if (s.exists(ancestorRef) && !principal.isAdministrator()
                    && !comment.getPropertyValue(COMMENT_AUTHOR).equals(principal.getName())
                    && !s.hasPermission(principal, ancestorRef, SecurityConstants.EVERYTHING)) {
                throw new CommentSecurityException(
                        "The user " + principal.getName() + " can not delete comments of document " + parentId);
            }
            DocumentModel parent = s.getDocument(parentRef);
            s.removeDocument(commentRef);
            notifyEvent(s, CommentEvents.COMMENT_REMOVED, parent, comment);
        });
    }

    @Override
    public Comment getExternalComment(CoreSession session, String entityId) throws CommentNotFoundException {
        DocumentModel commentModel = getExternalCommentModel(session, entityId);
        if (commentModel == null) {
            throw new CommentNotFoundException("The external comment " + entityId + " does not exist.");
        }
        String parentId = (String) commentModel.getPropertyValue(COMMENT_PARENT_ID);
        if (!session.hasPermission(getAncestorRef(session, commentModel), SecurityConstants.READ)) {
            throw new CommentSecurityException("The user " + session.getPrincipal().getName()
                    + " does not have access to the comments of document " + parentId);
        }
        return Framework.doPrivileged(() -> Comments.newComment(commentModel));
    }

    @Override
    public Comment updateExternalComment(CoreSession session, String entityId, Comment comment)
            throws CommentNotFoundException {
        DocumentModel commentModel = getExternalCommentModel(session, entityId);
        if (commentModel == null) {
            throw new CommentNotFoundException("The external comment " + entityId + " does not exist.");
        }
        NuxeoPrincipal principal = session.getPrincipal();
        if (!principal.isAdministrator() && !comment.getAuthor().equals(principal.getName())) {
            throw new CommentSecurityException(
                    "The user " + principal.getName() + " can not edit comments of document " + comment.getParentId());
        }
        return CoreInstance.doPrivileged(session, s -> {
            Comments.commentToDocumentModel(comment, commentModel);
            if (comment instanceof ExternalEntity) {
                Comments.externalEntityToDocumentModel((ExternalEntity) comment, commentModel);
            }
            s.saveDocument(commentModel);
            return Comments.newComment(commentModel);
        });
    }

    @Override
    public void deleteExternalComment(CoreSession session, String entityId) throws CommentNotFoundException {
        DocumentModel commentModel = getExternalCommentModel(session, entityId);
        if (commentModel == null) {
            throw new CommentNotFoundException("The external comment " + entityId + " does not exist.");
        }
        NuxeoPrincipal principal = session.getPrincipal();
        String parentId = (String) commentModel.getPropertyValue(COMMENT_PARENT_ID);
        if (!principal.isAdministrator() && !commentModel.getPropertyValue(COMMENT_AUTHOR).equals(principal.getName())
                && !session.hasPermission(principal, getAncestorRef(session, commentModel),
                        SecurityConstants.EVERYTHING)) {
            throw new CommentSecurityException(
                    "The user " + principal.getName() + " can not delete comments of document " + parentId);
        }
        CoreInstance.doPrivileged(session, s -> {
            DocumentModel comment = s.getDocument(commentModel.getRef());
            DocumentModel parent = s.getDocument(new IdRef((String) comment.getPropertyValue(COMMENT_PARENT_ID)));
            s.removeDocument(commentModel.getRef());
            notifyEvent(s, CommentEvents.COMMENT_REMOVED, parent, comment);
        });
    }

    @Override
    public boolean hasFeature(Feature feature) {
        switch (feature) {
        case COMMENTS_LINKED_WITH_PROPERTY:
            return true;
        default:
            throw new UnsupportedOperationException(feature.name());
        }
    }

    @SuppressWarnings("unchecked")
    protected DocumentModel getExternalCommentModel(CoreSession session, String entityId) {
        PageProviderService ppService = Framework.getService(PageProviderService.class);
        Map<String, Serializable> props = singletonMap(CORE_SESSION_PROPERTY, (Serializable) session);
        List<DocumentModel> results = ((PageProvider<DocumentModel>) ppService.getPageProvider(
                GET_COMMENT_PAGEPROVIDER_NAME, null, 1L, 0L, props, entityId)).getCurrentPage();
        if (results.isEmpty()) {
            return null;
        }
        return results.get(0);
    }

    protected String getCommentContainerPath(CoreSession session, String commentedDocumentId) {
        return CoreInstance.doPrivileged(session, s -> {
            // Create or retrieve the folder to store the comment.
            // If the document is under a domain, the folder is a child of this domain.
            // Otherwise, it is a child of the root document.
            DocumentModel annotatedDoc = s.getDocument(new IdRef(commentedDocumentId));
            String parentPath = "/";
            if (annotatedDoc.getPath().segmentCount() > 1) {
                parentPath += annotatedDoc.getPath().segment(0);
            }
            PathRef ref = new PathRef(parentPath, COMMENTS_DIRECTORY);
            DocumentModel commentFolderDoc = s.createDocumentModel(parentPath, COMMENTS_DIRECTORY, HIDDEN_FOLDER_TYPE);
            s.getOrCreateDocument(commentFolderDoc);
            s.save();
            return ref.toString();
        });
    }

    protected DocumentRef getAncestorRef(CoreSession session, DocumentModel documentModel) {
        return CoreInstance.doPrivileged(session, s -> {
            if (!documentModel.hasSchema(COMMENT_SCHEMA)) {
                return documentModel.getRef();
            }
            DocumentModel ancestorComment = getThreadForComment(s, documentModel);
            return new IdRef((String) ancestorComment.getPropertyValue(COMMENT_PARENT_ID));
        });
    }

    protected DocumentModel getThreadForComment(CoreSession session, DocumentModel comment)
            throws CommentSecurityException {

        NuxeoPrincipal principal = session.getPrincipal();
        return CoreInstance.doPrivileged(session, s -> {
            DocumentModel thread = comment;
            DocumentModel parent = s.getDocument(new IdRef((String) thread.getPropertyValue(COMMENT_PARENT_ID)));
            if (parent.hasSchema(COMMENT_SCHEMA)) {
                thread = getThreadForComment(parent);
            }
            DocumentRef ancestorRef = s.getDocument(new IdRef((String) thread.getPropertyValue(COMMENT_PARENT_ID)))
                                       .getRef();
            if (!s.hasPermission(principal, ancestorRef, SecurityConstants.READ)) {
                throw new CommentSecurityException("The user " + principal.getName()
                        + " does not have access to the comments of document " + ancestorRef.reference());
            }
            return thread;
        });
    }
}
