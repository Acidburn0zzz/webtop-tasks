/* 
 * Copyright (C) 2014 Sonicle S.r.l.
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License version 3 as published by
 * the Free Software Foundation with the addition of the following permission
 * added to Section 15 as permitted in Section 7(a): FOR ANY PART OF THE COVERED
 * WORK IN WHICH THE COPYRIGHT IS OWNED BY SONICLE, SONICLE DISCLAIMS THE
 * WARRANTY OF NON INFRINGEMENT OF THIRD PARTY RIGHTS.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program; if not, see http://www.gnu.org/licenses or write to
 * the Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301 USA.
 *
 * You can contact Sonicle S.r.l. at email address sonicle[at]sonicle[dot]com
 *
 * The interactive user interfaces in modified source and object code versions
 * of this program must display Appropriate Legal Notices, as required under
 * Section 5 of the GNU Affero General Public License version 3.
 *
 * In accordance with Section 7(b) of the GNU Affero General Public License
 * version 3, these Appropriate Legal Notices must retain the display of the
 * Sonicle logo and Sonicle copyright notice. If the display of the logo is not
 * reasonably feasible for technical reasons, the Appropriate Legal Notices must
 * display the words "Copyright (C) 2014 Sonicle S.r.l.".
 */
package com.sonicle.webtop.tasks;

import com.sonicle.commons.EnumUtils;
import com.sonicle.commons.db.DbUtils;
import com.sonicle.commons.web.json.CompositeId;
import com.sonicle.webtop.core.CoreManager;
import com.sonicle.webtop.core.app.RunContext;
import com.sonicle.webtop.core.app.WT;
import com.sonicle.webtop.core.bol.OShare;
import com.sonicle.webtop.core.bol.Owner;
import com.sonicle.webtop.core.model.IncomingShareRoot;
import com.sonicle.webtop.core.model.SharePermsElements;
import com.sonicle.webtop.core.model.SharePermsFolder;
import com.sonicle.webtop.core.model.SharePermsRoot;
import com.sonicle.webtop.core.bol.model.Sharing;
import com.sonicle.webtop.core.dal.DAOException;
import com.sonicle.webtop.core.dal.DAOIntegrityViolationException;
import com.sonicle.webtop.core.sdk.AbstractMapCache;
import com.sonicle.webtop.core.sdk.AbstractShareCache;
import com.sonicle.webtop.core.sdk.AuthException;
import com.sonicle.webtop.core.sdk.BaseManager;
import com.sonicle.webtop.core.sdk.BaseReminder;
import com.sonicle.webtop.core.sdk.ReminderEmail;
import com.sonicle.webtop.core.sdk.ReminderInApp;
import com.sonicle.webtop.core.sdk.UserProfile;
import com.sonicle.webtop.core.sdk.UserProfileId;
import com.sonicle.webtop.core.sdk.WTException;
import com.sonicle.webtop.core.sdk.WTRuntimeException;
import com.sonicle.webtop.core.util.IdentifierUtils;
import com.sonicle.webtop.tasks.bol.OCategory;
import com.sonicle.webtop.tasks.bol.OCategoryPropSet;
import com.sonicle.webtop.tasks.bol.OTask;
import com.sonicle.webtop.tasks.bol.VTask;
import com.sonicle.webtop.tasks.bol.model.MyShareRootCategory;
import com.sonicle.webtop.tasks.model.ShareFolderCategory;
import com.sonicle.webtop.tasks.model.ShareRootCategory;
import com.sonicle.webtop.tasks.model.Task;
import com.sonicle.webtop.tasks.dal.CategoryDAO;
import com.sonicle.webtop.tasks.dal.CategoryPropsDAO;
import com.sonicle.webtop.tasks.dal.TaskDAO;
import com.sonicle.webtop.tasks.model.Category;
import com.sonicle.webtop.tasks.model.CategoryPropSet;
import com.sonicle.webtop.tasks.model.FolderTasks;
import com.sonicle.webtop.tasks.model.TaskEx;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;

/**
 *
 * @author malbinola
 */
public class TasksManager extends BaseManager implements ITasksManager {
	public static final Logger logger = WT.getLogger(TasksManager.class);
	public static final String GROUPNAME_CATEGORY = "CATEGORY";
	public static final String SUGGESTION_TASK_SUBJECT = "tasksubject";
	
	private final OwnerCache ownerCache = new OwnerCache();
	private final ShareCache shareCache = new ShareCache();
	
	public TasksManager(boolean fastInit, UserProfileId targetProfileId) {
		super(fastInit, targetProfileId);
		if (!fastInit) {
			shareCache.init();
		}
	}
	
	private TasksServiceSettings getServiceSettings() {
		return new TasksServiceSettings(SERVICE_ID, getTargetProfileId().getDomainId());
	}
	
	private List<ShareRootCategory> internalListIncomingCategoryShareRoots() throws WTException {
		CoreManager coreMgr = WT.getCoreManager(getTargetProfileId());
		List<ShareRootCategory> roots = new ArrayList();
		HashSet<String> hs = new HashSet<>();
		for (IncomingShareRoot share : coreMgr.listIncomingShareRoots(SERVICE_ID, GROUPNAME_CATEGORY)) {
			final SharePermsRoot perms = coreMgr.getShareRootPermissions(share.getShareId());
			ShareRootCategory root = new ShareRootCategory(share, perms);
			if (hs.contains(root.getShareId())) continue; // Avoid duplicates ??????????????????????
			hs.add(root.getShareId());
			roots.add(root);
		}
		return roots;
	}
	
	public String buildSharingId(int categoryId) throws WTException {
		UserProfileId targetPid = getTargetProfileId();
		
		// Skip rights check if running user is resource's owner
		UserProfileId owner = ownerCache.get(categoryId);
		if (owner == null) throw new WTException("owner({0}) -> null", categoryId);
		
		String rootShareId = null;
		if (owner.equals(targetPid)) {
			rootShareId = MyShareRootCategory.SHARE_ID;
		} else {
			rootShareId = shareCache.getShareRootIdByFolderId(categoryId);
		}
		if (rootShareId == null) throw new WTException("Unable to find a root share [{0}]", categoryId);
		return new CompositeId().setTokens(rootShareId, categoryId).toString();
	}
	
	public Sharing getSharing(String shareId) throws WTException {
		CoreManager core = WT.getCoreManager();
		return core.getSharing(SERVICE_ID, GROUPNAME_CATEGORY, shareId);
	}
	
	public void updateSharing(Sharing sharing) throws WTException {
		CoreManager core = WT.getCoreManager();
		core.updateSharing(SERVICE_ID, GROUPNAME_CATEGORY, sharing);
	}
	
	public UserProfileId getCategoryOwner(int categoryId) throws WTException {
		return ownerCache.get(categoryId);
	}
	
	@Override
	public List<ShareRootCategory> listIncomingCategoryRoots() {
		return shareCache.getShareRoots();
	}
	
	@Override
	public Map<Integer, ShareFolderCategory> listIncomingCategoryFolders(String rootShareId) throws WTException {
		CoreManager coreMgr = WT.getCoreManager(getTargetProfileId());
		LinkedHashMap<Integer, ShareFolderCategory> folders = new LinkedHashMap<>();
		
		for (Integer folderId : shareCache.getFolderIdsByShareRoot(rootShareId)) {
			final String shareFolderId = shareCache.getShareFolderIdByFolderId(folderId);
			if (StringUtils.isBlank(shareFolderId)) continue;
			SharePermsFolder fperms = coreMgr.getShareFolderPermissions(shareFolderId);
			SharePermsElements eperms = coreMgr.getShareElementsPermissions(shareFolderId);
			if (folders.containsKey(folderId)) {
				final ShareFolderCategory shareFolder = folders.get(folderId);
				if (shareFolder == null) continue;
				shareFolder.getPerms().merge(fperms);
				shareFolder.getElementsPerms().merge(eperms);
			} else {
				final Category category = getCategory(folderId);
				if (category == null) continue;
				folders.put(folderId, new ShareFolderCategory(shareFolderId, fperms, eperms, category));
			}
		}
		return folders;
	}
	
	@Override
	public List<Integer> listCategoryIds() throws WTException {
		ArrayList<Integer> ids = new ArrayList<>();
		for (Category category : listCategories()) {
			ids.add(category.getCategoryId());
		}
		return ids;
	}
	
	@Override
	public List<Integer> listIncomingCategoryIds() throws WTException {
		return shareCache.getFolderIds();
	}
	
	@Override
	public List<Category> listCategories() throws WTException {
		return listCategories(getTargetProfileId());
	}
	
	private List<Category> listCategories(UserProfileId pid) throws WTException {
		CategoryDAO catDao = CategoryDAO.getInstance();
		ArrayList<Category> items = new ArrayList<>();
		Connection con = null;
		
		try {
			con = WT.getConnection(SERVICE_ID);
			for (OCategory ocat : catDao.selectByProfile(con, pid.getDomainId(), pid.getUserId())) {
				items.add(createCategory(ocat));
			}
			return items;
			
		} catch(SQLException | DAOException ex) {
			throw new WTException(ex, "DB error");
		} finally {
			DbUtils.closeQuietly(con);
		}
	}
	
	@Override
	public Category getCategory(int categoryId) throws WTException {
		CategoryDAO catDao = CategoryDAO.getInstance();
		Connection con = null;
		
		try {
			checkRightsOnCategoryFolder(categoryId, "READ");
			
			con = WT.getConnection(SERVICE_ID);
			OCategory ocat = catDao.selectById(con, categoryId);
			
			return createCategory(ocat);
			
		} catch(SQLException | DAOException ex) {
			throw new WTException(ex, "DB error");
		} finally {
			DbUtils.closeQuietly(con);
		}
	}
	
	@Override
	public Category getBuiltInCategory() throws WTException {
		CategoryDAO catDao = CategoryDAO.getInstance();
		Connection con = null;
		
		try {
			con = WT.getConnection(SERVICE_ID);
			OCategory ocat = catDao.selectBuiltInByProfile(con, getTargetProfileId().getDomainId(), getTargetProfileId().getUserId());
			if(ocat == null) return null;
			
			checkRightsOnCategoryFolder(ocat.getCategoryId(), "READ");
			
			return createCategory(ocat);
			
		} catch(SQLException | DAOException ex) {
			throw new WTException(ex, "DB error");
		} finally {
			DbUtils.closeQuietly(con);
		}
	}
	
	@Override
	public Category addCategory(Category category) throws WTException {
		Connection con = null;
		
		try {
			checkRightsOnCategoryRoot(category.getProfileId(), "MANAGE");
			
			con = WT.getConnection(SERVICE_ID, false);
			category.setBuiltIn(false);
			category = doCategoryUpdate(true, con, category);
			DbUtils.commitQuietly(con);
			writeLog("CATEGORY_INSERT", String.valueOf(category.getCategoryId()));
			
			return category;
			
		} catch(SQLException | DAOException ex) {
			DbUtils.rollbackQuietly(con);
			throw new WTException(ex, "DB error");
		} catch(Exception ex) {
			DbUtils.rollbackQuietly(con);
			throw ex;
		} finally {
			DbUtils.closeQuietly(con);
		}
	}
	
	@Override
	public Category addBuiltInCategory() throws WTException {
		CategoryDAO dao = CategoryDAO.getInstance();
		Connection con = null;
		
		try {
			checkRightsOnCategoryRoot(getTargetProfileId(), "MANAGE");
			
			con = WT.getConnection(SERVICE_ID, false);
			OCategory ocat = dao.selectBuiltInByProfile(con, getTargetProfileId().getDomainId(), getTargetProfileId().getUserId());
			if (ocat != null) {
				logger.debug("Built-in category already present");
				return null;
			}
			
			Category cat = new Category();
			cat.setBuiltIn(true);
			cat.setName(WT.getPlatformName());
			cat.setDescription("");
			cat.setIsDefault(true);
			cat = doCategoryUpdate(true, con, cat);
			DbUtils.commitQuietly(con);
			writeLog("CATEGORY_INSERT", String.valueOf(cat.getCategoryId()));
			
			return cat;
			
		} catch(SQLException | DAOException ex) {
			DbUtils.rollbackQuietly(con);
			throw new WTException(ex, "DB error");
		} catch(Exception ex) {
			DbUtils.rollbackQuietly(con);
			throw ex;
		} finally {
			DbUtils.closeQuietly(con);
		}
	}
	
	@Override
	public Category updateCategory(Category cat) throws Exception {
		Connection con = null;
		
		try {
			checkRightsOnCategoryFolder(cat.getCategoryId(), "UPDATE");
			
			con = WT.getConnection(SERVICE_ID, false);
			cat = doCategoryUpdate(false, con, cat);
			DbUtils.commitQuietly(con);
			writeLog("CATEGORY_UPDATE", String.valueOf(cat.getCategoryId()));
			
			return cat;
			
		} catch(SQLException | DAOException ex) {
			DbUtils.rollbackQuietly(con);
			throw new WTException(ex, "DB error");
		} catch(Exception ex) {
			DbUtils.rollbackQuietly(con);
			throw ex;
		} finally {
			DbUtils.closeQuietly(con);
		}
	}
	
	@Override
	public boolean deleteCategory(int categoryId) throws WTException {
		CategoryDAO catDao = CategoryDAO.getInstance();
		CategoryPropsDAO psetDao = CategoryPropsDAO.getInstance();
		Connection con = null;
		
		try {
			checkRightsOnCategoryFolder(categoryId, "DELETE");
			
			// Retrieve sharing status (for later)
			String sharingId = buildSharingId(categoryId);
			Sharing sharing = getSharing(sharingId);

			con = WT.getConnection(SERVICE_ID, false);
			int ret = catDao.deleteById(con, categoryId);
			psetDao.deleteByCategory(con, categoryId);
			doDeleteTasksByCategory(con, categoryId);
			
			// Cleanup sharing, if necessary
			if ((sharing != null) && !sharing.getRights().isEmpty()) {
				logger.debug("Removing {} active sharing [{}]", sharing.getRights().size(), sharing.getId());
				sharing.getRights().clear();
				updateSharing(sharing);
			}
			
			DbUtils.commitQuietly(con);
			
			final String ref = String.valueOf(categoryId);
			writeLog("CATEGORY_DELETE", ref);
			writeLog("TASK_DELETE", "*@"+ref);
			
			return ret == 1;
			
		} catch(SQLException | DAOException ex) {
			DbUtils.rollbackQuietly(con);
			throw new WTException(ex, "DB error");
		} catch(Exception ex) {
			DbUtils.rollbackQuietly(con);
			throw ex;
		} finally {
			DbUtils.closeQuietly(con);
		}
	}
	
	@Override
	public CategoryPropSet getCategoryCustomProps(int categoryId) throws WTException {
		return getCategoryCustomProps(getTargetProfileId(), categoryId);
	}
	
	private CategoryPropSet getCategoryCustomProps(UserProfileId profileId, int categoryId) throws WTException {
		CategoryPropsDAO psetDao = CategoryPropsDAO.getInstance();
		Connection con = null;
		
		try {
			con = WT.getConnection(SERVICE_ID);
			OCategoryPropSet opset = psetDao.selectByProfileCategory(con, profileId.getDomainId(), profileId.getUserId(), categoryId);
			return (opset == null) ? new CategoryPropSet() : createCategoryPropSet(opset);
			
		} catch(SQLException | DAOException ex) {
			throw new WTException(ex, "DB error");
		} finally {
			DbUtils.closeQuietly(con);
		}
	}
	
	@Override
	public Map<Integer, CategoryPropSet> getCategoryCustomProps(Collection<Integer> categoryIds) throws WTException {
		return getCategoryCustomProps(getTargetProfileId(), categoryIds);
	}
	
	public Map<Integer, CategoryPropSet> getCategoryCustomProps(UserProfileId profileId, Collection<Integer> categoryIds) throws WTException {
		CategoryPropsDAO psetDao = CategoryPropsDAO.getInstance();
		Connection con = null;
		
		try {
			con = WT.getConnection(SERVICE_ID);
			LinkedHashMap<Integer, CategoryPropSet> psets = new LinkedHashMap<>(categoryIds.size());
			Map<Integer, OCategoryPropSet> map = psetDao.selectByProfileCategoryIn(con, profileId.getDomainId(), profileId.getUserId(), categoryIds);
			for (Integer categoryId : categoryIds) {
				OCategoryPropSet opset = map.get(categoryId);
				psets.put(categoryId, (opset == null) ? new CategoryPropSet() : createCategoryPropSet(opset));
			}
			return psets;
			
		} catch(SQLException | DAOException ex) {
			throw new WTException(ex, "DB error");
		} finally {
			DbUtils.closeQuietly(con);
		}
	}
	
	@Override
	public CategoryPropSet updateCategoryCustomProps(int categoryId, CategoryPropSet propertySet) throws WTException {
		ensureUser();
		return updateCategoryCustomProps(getTargetProfileId(), categoryId, propertySet);
	}
	
	private CategoryPropSet updateCategoryCustomProps(UserProfileId profileId, int categoryId, CategoryPropSet propertySet) throws WTException {
		CategoryPropsDAO psetDao = CategoryPropsDAO.getInstance();
		Connection con = null;
		
		try {
			OCategoryPropSet opset = createOCategoryPropSet(propertySet);
			opset.setDomainId(profileId.getDomainId());
			opset.setUserId(profileId.getUserId());
			opset.setCategoryId(categoryId);
			
			con = WT.getConnection(SERVICE_ID);
			try {
				psetDao.insert(con, opset);
			} catch(DAOIntegrityViolationException ex1) {
				psetDao.update(con, opset);
			}
			return propertySet;
			
		} catch(SQLException | DAOException ex) {
			throw new WTException(ex, "DB error");
		} finally {
			DbUtils.closeQuietly(con);
		}
	}
	
	@Override
	public List<FolderTasks> listFolderTasks(Collection<Integer> categoryIds, String pattern) throws WTException {
		CategoryDAO catDao = CategoryDAO.getInstance();
		TaskDAO tasDao = TaskDAO.getInstance();
		Connection con = null;
		
		try {
			con = WT.getConnection(SERVICE_ID);
			
			// TODO: implementare filtro task privati
			ArrayList<FolderTasks> foTasks = new ArrayList<>();
			List<OCategory> ocats = catDao.selectByDomainIn(con, getTargetProfileId().getDomainId(), categoryIds);
			for (OCategory ocat : ocats) {
				if (!quietlyCheckRightsOnCategoryFolder(ocat.getCategoryId(), "READ")) continue;
				
				final List<VTask> vtasks = tasDao.viewByCategoryPattern(con, ocat.getCategoryId(), pattern);
				final ArrayList<TaskEx> tasks = new ArrayList<>();
				for (VTask vtask : vtasks) {
					tasks.add(fillTaskEx(new TaskEx(), vtask));
				}
				foTasks.add(new FolderTasks(createCategory(ocat), tasks));
			}
			return foTasks;
			
		} catch (SQLException | DAOException ex) {
			throw new WTException(ex, "DB error");
		} finally {
			DbUtils.closeQuietly(con);
		}
	}
	
	public List<TaskEx> listUpcomingTasks(Collection<Integer> categoryFolderIds) throws WTException {
		return listUpcomingTasks(categoryFolderIds, null);
	}
	
	public List<TaskEx> listUpcomingTasks(Collection<Integer> categoryFolderIds, String pattern) throws WTException {
		TaskDAO tasDao = TaskDAO.getInstance();
		Connection con = null;
		
		try {
			con = WT.getConnection(SERVICE_ID);
			
			ArrayList<Integer> validIds = new ArrayList<>();
			for(Integer catId : categoryFolderIds) {
				if (!quietlyCheckRightsOnCategoryFolder(catId, "READ")) continue;
				validIds.add(catId);
			}
			
			ArrayList<TaskEx> items = new ArrayList<>();
			List<VTask> vtasks = tasDao.viewUpcomingByCategoriesPattern(con, validIds, pattern);
			for(VTask vtask : vtasks) {
				items.add(fillTaskEx(new TaskEx(), vtask));
			}
			
			return items;
			
		} catch(SQLException | DAOException ex) {
			throw new WTException(ex, "DB error");
		} finally {
			DbUtils.closeQuietly(con);
		}
	}
    
	@Override
	public Task getTask(int taskId) throws WTException {
		TaskDAO tasdao = TaskDAO.getInstance();
		Connection con = null;
		
		try {
			con = WT.getConnection(SERVICE_ID);
			
			OTask otask = tasdao.selectById(con, taskId);
			if (otask == null) return null;
			checkRightsOnCategoryFolder(otask.getCategoryId(), "READ");
			
			return createTask(otask);
		
		} catch(SQLException | DAOException ex) {
			throw new WTException(ex, "DB error");
		} catch(WTException ex) {
			throw ex;
		} finally {
			DbUtils.closeQuietly(con);
		}
	}
	
	@Override
	public void addTask(Task task) throws WTException {
		CoreManager coreMgr = WT.getCoreManager(getTargetProfileId());
		Connection con = null;
		
		try {
			checkRightsOnCategoryElements(task.getCategoryId(), "CREATE"); // Rights check!
			
			con = WT.getConnection(SERVICE_ID, false);
			OTask otask = doUpdateTask(true, con, task);
			DbUtils.commitQuietly(con);
			writeLog("TASK_INSERT", String.valueOf(otask.getTaskId()));
			
			storeAsSuggestion(coreMgr, SUGGESTION_TASK_SUBJECT, task.getSubject());
			
		} catch(SQLException | DAOException ex) {
			DbUtils.rollbackQuietly(con);
			throw new WTException(ex, "DB error");
		} catch(Exception ex) {
			DbUtils.rollbackQuietly(con);
			throw ex;
		} finally {
			DbUtils.closeQuietly(con);
		}
	}
	
	@Override
	public void updateTask(Task task) throws WTException {
		Connection con = null;
		
		//TODO: gestire i suggerimenti (soggetto)

		try {
			checkRightsOnCategoryElements(task.getCategoryId(), "UPDATE"); // Rights check!

			con = WT.getConnection(SERVICE_ID, false);
			doUpdateTask(false, con, task);
			DbUtils.commitQuietly(con);
			writeLog("TASK_UPDATE", String.valueOf(task.getTaskId()));

		} catch (SQLException | DAOException ex) {
			DbUtils.rollbackQuietly(con);
			throw new WTException(ex, "DB error");
		} catch (Exception ex) {
			DbUtils.rollbackQuietly(con);
			throw ex;
		} finally {
			DbUtils.closeQuietly(con);
		}
	}
	
	@Override
	public void deleteTask(int taskId) throws WTException {
		TaskDAO tasdao = TaskDAO.getInstance();
		Connection con = null;

		try {
			con = WT.getConnection(SERVICE_ID);

			OTask cont = tasdao.selectById(con, taskId);
			if (cont == null) throw new WTException("Unable to retrieve task [{0}]", taskId);
			checkRightsOnCategoryElements(cont.getCategoryId(), "DELETE"); // Rights check!

			con.setAutoCommit(false);
			doDeleteTask(con, taskId);
			DbUtils.commitQuietly(con);
			writeLog("TASK_DELETE", String.valueOf(taskId));

		} catch (SQLException | DAOException ex) {
			DbUtils.rollbackQuietly(con);
			throw new WTException(ex, "DB error");
		} catch (Exception ex) {
			DbUtils.rollbackQuietly(con);
			throw ex;
		} finally {
			DbUtils.closeQuietly(con);
		}
	}
	
	@Override
	public void deleteTask(ArrayList<Integer> taskIds) throws WTException {
		TaskDAO tasdao = TaskDAO.getInstance();
		Connection con = null;
		
		try {
			con = WT.getConnection(SERVICE_ID);
			con.setAutoCommit(false);
			
			for(Integer taskId : taskIds) {
				if (taskId == null) continue;
				OTask task = tasdao.selectById(con, taskId);
				if (task == null) throw new WTException("Unable to retrieve task [{0}]", taskId);
				checkRightsOnCategoryElements(task.getCategoryId(), "DELETE"); // Rights check!
				
				doDeleteTask(con, taskId);
			}
			
			DbUtils.commitQuietly(con);
			writeLog("TASK_DELETE", "*");
			
		} catch(SQLException | DAOException ex) {
			DbUtils.rollbackQuietly(con);
			throw new WTException(ex, "DB error");
		} catch(Exception ex) {
			DbUtils.rollbackQuietly(con);
			throw ex;
		} finally {
			DbUtils.closeQuietly(con);
		}
	}
	
	@Override
	public int deleteAllTasks(int categoryId) throws WTException {
		Connection con = null;

		try {
			checkRightsOnCategoryElements(categoryId, "DELETE");

			con = WT.getConnection(SERVICE_ID, false);
			int ret = doDeleteTasksByCategory(con, categoryId);
			DbUtils.commitQuietly(con);
			writeLog("TASK_DELETE", "*");
			
			return ret;

		} catch (SQLException | DAOException ex) {
			DbUtils.rollbackQuietly(con);
			throw new WTException(ex, "DB error");
		} catch (Exception ex) {
			DbUtils.rollbackQuietly(con);
			throw ex;
		} finally {
			DbUtils.closeQuietly(con);
		}
	}
	
	@Override
	public void moveTask(boolean copy, int taskId, int targetCategoryId) throws WTException {
		TaskDAO tasdao = TaskDAO.getInstance();
		Connection con = null;
		
		try {
			con = WT.getConnection(SERVICE_ID);
			OTask otask = tasdao.selectById(con, taskId);
			if (otask == null) throw new WTException("Unable to retrieve task [{0}]", taskId);
			checkRightsOnCategoryFolder(otask.getCategoryId(), "READ");
			
			if (copy || (targetCategoryId != otask.getCategoryId())) {
				checkRightsOnCategoryElements(targetCategoryId, "CREATE");
				if (!copy) checkRightsOnCategoryElements(otask.getCategoryId(), "DELETE");
				
				Task task = createTask(otask);

				con.setAutoCommit(false);
				doMoveTask(con, copy, task, targetCategoryId);
				DbUtils.commitQuietly(con);
				writeLog("TASK_UPDATE", String.valueOf(task.getTaskId()));
			}
			
		} catch(SQLException | DAOException ex) {
			DbUtils.rollbackQuietly(con);
			throw new WTException(ex, "DB error");
		} catch(Exception ex) {
			DbUtils.rollbackQuietly(con);
			throw ex;
		} finally {
			DbUtils.closeQuietly(con);
		}
	}
	
	public void eraseData(boolean deep) throws WTException {
		CategoryDAO catDao = CategoryDAO.getInstance();
		CategoryPropsDAO psetDao = CategoryPropsDAO.getInstance();
		TaskDAO tasDao = TaskDAO.getInstance();
		Connection con = null;
		
		//TODO: controllo permessi
		
		try {
			con = WT.getConnection(SERVICE_ID, false);
			UserProfileId pid = getTargetProfileId();
			
			// Erase tasks
			if (deep) {
				for (OCategory ocat : catDao.selectByProfile(con, pid.getDomainId(), pid.getUserId())) {
					tasDao.deleteByCategoryId(con, ocat.getCategoryId());
				}
			} else {
				DateTime revTs = createRevisionTimestamp();
				for (OCategory ocat : catDao.selectByProfile(con, pid.getDomainId(), pid.getUserId())) {
					tasDao.logicDeleteByCategoryId(con, ocat.getCategoryId(), revTs);
				}
			}
			
			// Erase categories
			psetDao.deleteByProfile(con, pid.getDomainId(), pid.getUserId());
			catDao.deleteByProfile(con, pid.getDomainId(), pid.getUserId());
			
			DbUtils.commitQuietly(con);
			
		} catch(SQLException | DAOException ex) {
			DbUtils.rollbackQuietly(con);
			throw new WTException(ex, "DB error");
		} catch(Exception ex) {
			DbUtils.rollbackQuietly(con);
			throw ex;
		} finally {
			DbUtils.closeQuietly(con);
		}
	}

	public List<BaseReminder> getRemindersToBeNotified(DateTime now) {
		ArrayList<BaseReminder> alerts = new ArrayList<>();
		HashMap<UserProfileId, Boolean> byEmailCache = new HashMap<>();
		TaskDAO dao = TaskDAO.getInstance();
		Connection con = null;
		
		try {
			con = WT.getConnection(SERVICE_ID);
			con.setAutoCommit(false);
			
			DateTime now12 = now.plusHours(14);
			List<VTask> tasks = dao.viewExpridedForUpdateByUntil(con, now12);
			DateTime profileNow = null, profileReminderDate = null;
			for(VTask task : tasks) {
				UserProfile.Data ud = WT.getUserData(task.getCategoryProfileId());
				profileNow = now.withZone(ud.getTimeZone());
				profileReminderDate = task.getReminderDate().withZone(DateTimeZone.UTC).withZoneRetainFields(ud.getTimeZone());
				if(profileReminderDate.isAfter(profileNow)) continue;
				
				if(!byEmailCache.containsKey(task.getCategoryProfileId())) {
					TasksUserSettings us = new TasksUserSettings(SERVICE_ID, task.getCategoryProfileId());
					boolean bool = us.getTaskReminderDelivery().equals(TasksSettings.TASK_REMINDER_DELIVERY_EMAIL);
					byEmailCache.put(task.getCategoryProfileId(), bool);
				}

				int ret = dao.updateRemindedOn(con, task.getTaskId(), now);
				if(ret != 1) continue;
				
				if(byEmailCache.get(task.getCategoryProfileId())) {
					//UserProfile.Data ud = WT.getUserData(task.getCategoryProfileId());
					alerts.add(createTaskReminderAlertEmail(ud.getLocale(), task));
				} else {
					alerts.add(createTaskReminderAlertWeb(task, profileReminderDate, ud.getTimeZone()));
				}
			}
			DbUtils.commitQuietly(con);
			
		} catch(Exception ex) {
			logger.error("Error collecting reminder alerts", ex);
		} finally {
			DbUtils.closeQuietly(con);
		}
		return alerts;
	}
	
	private Category doCategoryUpdate(boolean insert, Connection con, Category cat) throws WTException {
		CategoryDAO catDao = CategoryDAO.getInstance();
		
		OCategory ocat = createOCategory(cat);
		if (insert) {
			ocat.setCategoryId(catDao.getSequence(con).intValue());
		}
		fillOCategoryWithDefaults(ocat);
		if (ocat.getIsDefault()) catDao.resetIsDefaultByProfile(con, ocat.getDomainId(), ocat.getUserId());
		if (insert) {
			catDao.insert(con, ocat);
		} else {
			catDao.update(con, ocat);
		}
		
		return createCategory(ocat);
	}
	
	private String buildTaskUid(int taskId, String internetName) {
		return buildTaskUid(IdentifierUtils.getUUIDTimeBased(true), taskId, internetName);
	}
	
	private String buildTaskUid(String timeBasedPart, int taskId, String internetName) {
		return buildTaskUid(timeBasedPart, DigestUtils.md5Hex(String.valueOf(taskId)), internetName);
	}
	
	private String buildTaskUid(String timeBasedPart, String taskPart, String internetName) {
		return timeBasedPart + "." + taskPart + "@" + internetName;
	}
	
	private OTask doUpdateTask(boolean insert, Connection con, Task task) throws WTException {
		TaskDAO tasDao = TaskDAO.getInstance();
		
		OTask otask = createOTask(task);
		if (insert) {
			otask.setTaskId(tasDao.getSequence(con).intValue());
		}
		fillOTaskWithDefaults(otask);
		if (insert) {
			tasDao.insert(con, otask, createRevisionTimestamp());
		} else {
			tasDao.update(con, otask, createRevisionTimestamp());
		}
		return otask;
	}
	
	private int doDeleteTask(Connection con, int taskId) throws WTException {
		TaskDAO tasdao = TaskDAO.getInstance();
		return tasdao.logicDeleteById(con, taskId, createRevisionTimestamp());
	}
	
	private int doDeleteTasksByCategory(Connection con, int categoryId) throws WTException {
		TaskDAO tasdao = TaskDAO.getInstance();
		return tasdao.logicDeleteByCategoryId(con, categoryId, createRevisionTimestamp());
	}
	
	private void doMoveTask(Connection con, boolean copy, Task task, int targetCategoryId) throws WTException {
		if(copy) {
			task.setCategoryId(targetCategoryId);
			doUpdateTask(true, con, task);
		} else {
			TaskDAO tasDao = TaskDAO.getInstance();
			tasDao.updateCategory(con, task.getTaskId(), targetCategoryId, createRevisionTimestamp());
		}
	}
	
	private UserProfileId findCategoryOwner(int categoryId) throws WTException {
		CategoryDAO dao = CategoryDAO.getInstance();
		Connection con = null;
		
		try {
			con = WT.getConnection(SERVICE_ID);
			Owner owner = dao.selectOwnerById(con, categoryId);
			return (owner == null) ? null : new UserProfileId(owner.getDomainId(), owner.getUserId());
			
		} catch(SQLException | DAOException ex) {
			throw new WTException(ex, "DB error");
		} finally {
			DbUtils.closeQuietly(con);
		}
	}
	
	private void checkRightsOnCategoryRoot(UserProfileId owner, String action) throws WTException {
		UserProfileId targetPid = getTargetProfileId();
		
		if (RunContext.isWebTopAdmin()) return;
		if (owner.equals(targetPid)) return;
		
		String shareId = shareCache.getShareRootIdByOwner(owner);
		if (shareId == null) throw new WTException("ownerToRootShareId({0}) -> null", owner);
		CoreManager core = WT.getCoreManager(targetPid);
		if (core.isShareRootPermitted(shareId, action)) return;
		//if(core.isShareRootPermitted(SERVICE_ID, RESOURCE_CATEGORY, action, shareId)) return;
		
		throw new AuthException("Action not allowed on root share [{0}, {1}, {2}, {3}]", shareId, action, GROUPNAME_CATEGORY, targetPid.toString());
	}
	
	private boolean quietlyCheckRightsOnCategoryFolder(int categoryId, String action) {
		try {
			checkRightsOnCategoryFolder(categoryId, action);
			return true;
		} catch(AuthException ex1) {
			return false;
		} catch(WTException ex1) {
			logger.warn("Unable to check rights [{}]", categoryId);
			return false;
		}
	}
	
	private void checkRightsOnCategoryFolder(int categoryId, String action) throws WTException {
		UserProfileId targetPid = getTargetProfileId();
		
		if (RunContext.isWebTopAdmin()) return;
		
		// Skip rights check if running user is resource's owner
		UserProfileId owner = ownerCache.get(categoryId);
		if (owner == null) throw new WTException("categoryToOwner({0}) -> null", categoryId);
		if (owner.equals(targetPid)) return;
		
		// Checks rights on the wildcard instance (if present)
		CoreManager core = WT.getCoreManager(targetPid);
		String wildcardShareId = shareCache.getWildcardShareFolderIdByOwner(owner);
		if (wildcardShareId != null) {
			if (core.isShareFolderPermitted(wildcardShareId, action)) return;
			//if(core.isShareFolderPermitted(SERVICE_ID, RESOURCE_CATEGORY, action, wildcardShareId)) return;
		}
		
		// Checks rights on category instance
		String shareId = shareCache.getShareFolderIdByFolderId(categoryId);
		if (shareId == null) throw new WTException("categoryToLeafShareId({0}) -> null", categoryId);
		if (core.isShareFolderPermitted(shareId, action)) return;
		// if(core.isShareFolderPermitted(SERVICE_ID, RESOURCE_CATEGORY, action, shareId)) return;
		
		throw new AuthException("Action not allowed on folder share [{0}, {1}, {2}, {3}]", shareId, action, GROUPNAME_CATEGORY, targetPid.toString());
	}
	
	private void checkRightsOnCategoryElements(int categoryId, String action) throws WTException {
		UserProfileId targetPid = getTargetProfileId();
		
		if (RunContext.isWebTopAdmin()) return;
		
		// Skip rights check if running user is resource's owner
		UserProfileId owner = ownerCache.get(categoryId);
		if (owner == null) throw new WTException("categoryToOwner({0}) -> null", categoryId);
		if (owner.equals(targetPid)) return;
		
		// Checks rights on the wildcard instance (if present)
		CoreManager core = WT.getCoreManager(targetPid);
		String wildcardShareId = shareCache.getWildcardShareFolderIdByOwner(owner);
		if (wildcardShareId != null) {
			if (core.isShareElementsPermitted(wildcardShareId, action)) return;
			//if (core.isShareElementsPermitted(SERVICE_ID, RESOURCE_CATEGORY, action, wildcardShareId)) return;
		}
		
		// Checks rights on calendar instance
		String shareId = shareCache.getShareFolderIdByFolderId(categoryId);
		if (shareId == null) throw new WTException("categoryToLeafShareId({0}) -> null", categoryId);
		if (core.isShareElementsPermitted(shareId, action)) return;
		//if (core.isShareElementsPermitted(SERVICE_ID, RESOURCE_CATEGORY, action, shareId)) return;
		
		throw new AuthException("Action not allowed on folderEls share [{0}, {1}, {2}, {3}]", shareId, action, GROUPNAME_CATEGORY, targetPid.toString());
	}
	
	private Category createCategory(OCategory with) {
		return fillCategory(new Category(), with);
	}
	
	private Category fillCategory(Category fill, OCategory with) {
		if ((fill != null) && (with != null)) {
			fill.setCategoryId(with.getCategoryId());
			fill.setDomainId(with.getDomainId());
			fill.setUserId(with.getUserId());
			fill.setBuiltIn(with.getBuiltIn());
			fill.setName(with.getName());
			fill.setDescription(with.getDescription());
			fill.setColor(with.getColor());
			fill.setSync(EnumUtils.forSerializedName(with.getSync(), Category.Sync.class));
			fill.setIsPrivate(with.getIsPrivate());
			fill.setIsDefault(with.getIsDefault());
		}
		return fill;
	}
	
	private OCategory createOCategory(Category with) {
		return fillOCategory(new OCategory(), with);
	}
	
	private OCategory fillOCategory(OCategory fill, Category with) {
		if ((fill != null) && (with != null)) {
			fill.setCategoryId(with.getCategoryId());
			fill.setDomainId(with.getDomainId());
			fill.setUserId(with.getUserId());
			fill.setBuiltIn(with.getBuiltIn());
			fill.setName(with.getName());
			fill.setDescription(with.getDescription());
			fill.setColor(with.getColor());
			fill.setSync(EnumUtils.toSerializedName(with.getSync()));
			fill.setIsPrivate(with.getIsPrivate());
			fill.setIsDefault(with.getIsDefault());
		}
		return fill;
	}
	
	private OCategory fillOCategoryWithDefaults(OCategory fill) {
		if (fill != null) {
			TasksServiceSettings ss = getServiceSettings();
			if (fill.getDomainId() == null) fill.setDomainId(getTargetProfileId().getDomainId());
			if (fill.getUserId() == null) fill.setUserId(getTargetProfileId().getUserId());
			if (fill.getBuiltIn() == null) fill.setBuiltIn(false);
			if (StringUtils.isBlank(fill.getColor())) fill.setColor("#FFFFFF");
			if (StringUtils.isBlank(fill.getSync())) fill.setSync(EnumUtils.toSerializedName(ss.getDefaultCategorySync()));
			if (fill.getIsDefault() == null) fill.setIsDefault(false);
			if (fill.getIsPrivate() == null) fill.setIsPrivate(false);
		}
		return fill;
	}
	
	private CategoryPropSet createCategoryPropSet(OCategoryPropSet with) {
		return fillCategoryPropSet(new CategoryPropSet(), with);
	}
	
	private CategoryPropSet fillCategoryPropSet(CategoryPropSet fill, OCategoryPropSet with) {
		if ((fill != null) && (with != null)) {
			fill.setHidden(with.getHidden());
			fill.setColor(with.getColor());
			fill.setSync(EnumUtils.forSerializedName(with.getSync(), Category.Sync.class));
		}
		return fill;
	}
	
	private OCategoryPropSet createOCategoryPropSet(CategoryPropSet with) {
		return fillOCategoryPropSet(new OCategoryPropSet(), with);
	}
	
	private OCategoryPropSet fillOCategoryPropSet(OCategoryPropSet fill, CategoryPropSet with) {
		if ((fill != null) && (with != null)) {
			fill.setHidden(with.getHidden());
			fill.setColor(with.getColor());
			fill.setSync(EnumUtils.toSerializedName(with.getSync()));
		}
		return fill;
	}
	
	private Task createTask(OTask with) {
		return fillTask(new Task(), with);
	}
	
	private Task fillTask(Task fill, OTask with) {
		if ((fill != null) && (with != null)) {
			fill.setTaskId(with.getTaskId());
			fill.setCategoryId(with.getCategoryId());
			fill.setRevisionStatus(EnumUtils.forSerializedName(with.getRevisionStatus(), Task.RevisionStatus.class));
			fill.setRevisionTimestamp(with.getRevisionTimestamp());
			fill.setPublicUid(with.getPublicUid());
			fill.setSubject(with.getSubject());
			fill.setDescription(with.getDescription());
			fill.setStartDate(with.getStartDate());
			fill.setDueDate(with.getDueDate());
			fill.setCompletedDate(with.getCompletedDate());
			fill.setImportance(with.getImportance());
			fill.setIsPrivate(with.getIsPrivate());
			fill.setStatus(EnumUtils.forSerializedName(with.getStatus(), Task.Status.class));
			fill.setCompletionPercentage(with.getCompletionPercentage());
			fill.setReminderDate(with.getReminderDate());
			fill.setRemindedOn(with.getRemindedOn());
		}
		return fill;
	}
	
	private OTask createOTask(Task with) {
		return fillOTask(new OTask(), with);
	}
	
	private OTask fillOTask(OTask fill, Task with) {
		if ((fill != null) && (with != null)) {
			fill.setTaskId(with.getTaskId());
			fill.setCategoryId(with.getCategoryId());
			fill.setRevisionStatus(EnumUtils.toSerializedName(with.getRevisionStatus()));
			fill.setRevisionTimestamp(with.getRevisionTimestamp());
			fill.setPublicUid(with.getPublicUid());
			fill.setSubject(with.getSubject());
			fill.setDescription(with.getDescription());
			fill.setStartDate(with.getStartDate());
			fill.setDueDate(with.getDueDate());
			fill.setCompletedDate(with.getCompletedDate());
			fill.setImportance(with.getImportance());
			fill.setIsPrivate(with.getIsPrivate());
			fill.setStatus(EnumUtils.toSerializedName(with.getStatus()));
			fill.setCompletionPercentage(with.getCompletionPercentage());
			fill.setReminderDate(with.getReminderDate());
			fill.setRemindedOn(with.getRemindedOn());
		}
		return fill;
	}
	
	private OTask fillOTaskWithDefaults(OTask fill) {
		if (fill != null) {
			if (StringUtils.isBlank(fill.getPublicUid())) {
				fill.setPublicUid(buildTaskUid(fill.getTaskId(), WT.getDomainInternetName(getTargetProfileId().getDomainId())));
			}
			if (fill.getImportance() == null) fill.setImportance((short)0);
			if (fill.getIsPrivate() == null) fill.setIsPrivate(false);
			if (fill.getStatus() == null) fill.setStatus(EnumUtils.toSerializedName(Task.Status.NOT_STARTED));
		}
		return fill;
	}
	
	private TaskEx fillTaskEx(TaskEx fill, VTask with) {
		if ((fill != null) && (with != null)) {
			fillTask(fill, with);
			fill.setCategoryDomainId(with.getCategoryDomainId());
			fill.setCategoryUserId(with.getCategoryUserId());
		}
		return fill;
	}
	
	private ReminderInApp createTaskReminderAlertWeb(VTask task, DateTime profileReminderDate, DateTimeZone profileTz) {
		ReminderInApp alert = new ReminderInApp(SERVICE_ID, task.getCategoryProfileId(), "task", String.valueOf(task.getTaskId()));
		alert.setTitle(task.getSubject());
		alert.setDate(profileReminderDate);
		alert.setTimezone(profileTz.getID());
		return alert;
	}
	
	private ReminderEmail createTaskReminderAlertEmail(Locale locale, VTask task) {
		ReminderEmail alert = new ReminderEmail(SERVICE_ID, task.getCategoryProfileId(), "task", String.valueOf(task.getTaskId()));
		//TODO: completare email
		return alert;
	}
	
	private void storeAsSuggestion(CoreManager coreMgr, String context, String value) {
		if (StringUtils.isBlank(value)) return;
		coreMgr.addServiceStoreEntry(SERVICE_ID, context, value.toUpperCase(), value);
	}
    
	private DateTime createRevisionTimestamp() {
		return DateTime.now(DateTimeZone.UTC);
	}
	
	private class OwnerCache extends AbstractMapCache<Integer, UserProfileId> {

		@Override
		protected void internalInitCache() {}

		@Override
		protected void internalMissKey(Integer key) {
			try {
				UserProfileId owner = findCategoryOwner(key);
				if (owner == null) throw new WTException("Owner not found [{0}]", key);
				put(key, owner);
			} catch(WTException ex) {
				throw new WTRuntimeException(ex.getMessage());
			}
		}
	}
	
	private class ShareCache extends AbstractShareCache<Integer, ShareRootCategory> {

		@Override
		protected void internalInitCache() {
			final CoreManager coreMgr = WT.getCoreManager(getTargetProfileId());
			try {
				for (ShareRootCategory root : internalListIncomingCategoryShareRoots()) {
					shareRoots.add(root);
					ownerToShareRoot.put(root.getOwnerProfileId(), root);
					for (OShare folder : coreMgr.listIncomingShareFolders(root.getShareId(), GROUPNAME_CATEGORY)) {
						if (folder.hasWildcard()) {
							final UserProfileId ownerPid = coreMgr.userUidToProfileId(folder.getUserUid());
							ownerToWildcardShareFolder.put(ownerPid, folder.getShareId().toString());
							for (Category category : listCategories(ownerPid)) {
								folderTo.add(category.getCategoryId());
								rootShareToFolderShare.put(root.getShareId(), category.getCategoryId());
								folderToWildcardShareFolder.put(category.getCategoryId(), folder.getShareId().toString());
							}
						} else {
							int categoryId = Integer.valueOf(folder.getInstance());
							folderTo.add(categoryId);
							rootShareToFolderShare.put(root.getShareId(), categoryId);
							folderToShareFolder.put(categoryId, folder.getShareId().toString());
						}
					}
				}
				ready = true;
			} catch(WTException ex) {
				throw new WTRuntimeException(ex.getMessage());
			}
		}
	}
}
