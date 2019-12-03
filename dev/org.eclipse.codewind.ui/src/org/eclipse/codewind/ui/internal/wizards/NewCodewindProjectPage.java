/*******************************************************************************
 * Copyright (c) 2019 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 *	 IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.codewind.ui.internal.wizards;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import org.eclipse.codewind.core.internal.CodewindManager;
import org.eclipse.codewind.core.internal.CoreUtil;
import org.eclipse.codewind.core.internal.Logger;
import org.eclipse.codewind.core.internal.ProcessHelper.ProcessResult;
import org.eclipse.codewind.core.internal.cli.InstallStatus;
import org.eclipse.codewind.core.internal.cli.InstallUtil;
import org.eclipse.codewind.core.internal.cli.TemplateUtil;
import org.eclipse.codewind.core.internal.connection.CodewindConnection;
import org.eclipse.codewind.core.internal.connection.CodewindConnectionManager;
import org.eclipse.codewind.core.internal.connection.ProjectTemplateInfo;
import org.eclipse.codewind.core.internal.connection.RegistryInfo;
import org.eclipse.codewind.core.internal.connection.RepositoryInfo;
import org.eclipse.codewind.core.internal.constants.ProjectLanguage;
import org.eclipse.codewind.core.internal.constants.ProjectType;
import org.eclipse.codewind.ui.internal.IDEUtil;
import org.eclipse.codewind.ui.internal.messages.Messages;
import org.eclipse.codewind.ui.internal.prefs.RegistryManagementDialog;
import org.eclipse.codewind.ui.internal.prefs.RepositoryManagementDialog;
import org.eclipse.codewind.ui.internal.views.ViewHelper;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.window.Window;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.dialogs.SearchPattern;
import org.eclipse.ui.dialogs.WizardNewProjectCreationPage;

public class NewCodewindProjectPage extends WizardNewProjectCreationPage {
	
	private static final Pattern projectNamePattern = Pattern.compile("^[a-zA-Z0-9_.-]+$"); //$NON-NLS-1$
			
	private CodewindConnection connection;
	private List<ProjectTemplateInfo> templateList;
	private SearchPattern pattern = new SearchPattern(SearchPattern.RULE_PATTERN_MATCH | SearchPattern.RULE_PREFIX_MATCH | SearchPattern.RULE_BLANK_MATCH);
	private Text filterText;
	private Table selectionTable;
	private Text descriptionLabel;
	private Text styleLabel;
	private Text sourceLabel;
	
	protected NewCodewindProjectPage(CodewindConnection connection, List<ProjectTemplateInfo> templateList) {
		super(Messages.NewProjectPage_ShellTitle);
		setTitle(Messages.NewProjectPage_WizardTitle);
		setDescription(Messages.NewProjectPage_WizardDescription);
		this.connection = connection;
		this.templateList = templateList;
		setPageComplete(false);
	}

	@Override
	public void createControl(Composite parent) {
		super.createControl(parent);
		Composite composite = (Composite) getControl();
		
		if (connection == null) {
			setupConnection();
			if (connection == null) {
				setErrorMessage(Messages.NewProjectPage_CodewindConnectError);
				setControl(composite);
				return;
			}
		}
		
		if (templateList == null || templateList.isEmpty()) {
			try {
				templateList = TemplateUtil.listTemplates(true, connection.getConid(), new NullProgressMonitor());
			} catch (Exception e) {
				Logger.logError("An error occurred trying to get the list of templates", e); //$NON-NLS-1$
				setErrorMessage(Messages.NewProjectPage_TemplateListError);
				setControl(composite);
				return;
			}
		}
		
		if (templateList.isEmpty()) {
			setErrorMessage(Messages.NewProjectPage_EmptyTemplateList);
		} else {
			templateList.sort(new Comparator<ProjectTemplateInfo>() {
				@Override
				public int compare(ProjectTemplateInfo info1, ProjectTemplateInfo info2) {
					return info1.getLabel().compareTo(info2.getLabel());
				}
			});
		}

		createContents(composite);
	}

	private void createContents(Composite parent) {
		
		// Project template composite
		Group templateGroup = new Group(parent, SWT.NONE);
		templateGroup.setText(Messages.NewProjectPage_TemplateGroupLabel);
		GridLayout layout = new GridLayout();
		layout.marginHeight = 8;
		layout.marginWidth = 8;
		layout.horizontalSpacing = 7;
		layout.verticalSpacing = 7;
		templateGroup.setLayout(layout);
		templateGroup.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, true, 2, 1));
		
		Text templateText = new Text(templateGroup, SWT.READ_ONLY);
		templateText.setText(Messages.NewProjectPage_TemplateGroupDesc);
		templateText.setLayoutData(new GridData(GridData.BEGINNING, GridData.CENTER, false, false));
		IDEUtil.normalizeBackground(templateText, templateGroup);
		
		// Filter text
		filterText = new Text(templateGroup, SWT.BORDER);
		filterText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		filterText.setMessage(Messages.NewProjectPage_FilterMessage);
		
		// Create a composite for the table so can use TableColumnLayout
		Composite tableComp = new Composite(templateGroup, SWT.NONE);
		TableColumnLayout tableColumnLayout = new TableColumnLayout();
		tableComp.setLayout(tableColumnLayout);
		GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
		data.heightHint = 200;
		tableComp.setLayoutData(data);

		// Table
		selectionTable = new Table(tableComp, SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.FULL_SELECTION);
		selectionTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		
		// Columns
		final TableColumn templateColumn = new TableColumn(selectionTable, SWT.NONE);
		templateColumn.setText(Messages.NewProjectPage_TemplateColumn);
		templateColumn.setResizable(true);
		templateColumn.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent event) {
				sortTable(selectionTable, templateColumn);
			}
		});
		final TableColumn typeColumn = new TableColumn(selectionTable, SWT.NONE);
		typeColumn.setText(Messages.NewProjectPage_TypeColumn);
		typeColumn.setResizable(true);
		typeColumn.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent event) {
				sortTable(selectionTable, typeColumn);
			}
		});
		final TableColumn languageColumn = new TableColumn(selectionTable, SWT.NONE);
		languageColumn.setText(Messages.NewProjectPage_LanguageColumn);
		languageColumn.setResizable(true);
		languageColumn.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent event) {
				sortTable(selectionTable, languageColumn);
			}
		});

		selectionTable.setHeaderVisible(true);
		selectionTable.setLinesVisible(false);
		selectionTable.setSortDirection(SWT.DOWN);
		selectionTable.setSortColumn(templateColumn);
		
		createItems(selectionTable, "");

		// Resize the columns
		Arrays.stream(selectionTable.getColumns()).forEach(TableColumn::pack);
		tableColumnLayout.setColumnData(templateColumn, new ColumnWeightData(10, Math.max(250, templateColumn.getWidth()), true));
		tableColumnLayout.setColumnData(typeColumn, new ColumnWeightData(4, Math.max(100, typeColumn.getWidth()), true));
		tableColumnLayout.setColumnData(languageColumn, new ColumnWeightData(3, Math.max(75, languageColumn.getWidth()), true));
		
		// Details
		ScrolledComposite detailsScroll = new ScrolledComposite(templateGroup, SWT.V_SCROLL);
		data = new GridData(GridData.FILL, GridData.FILL, true, true);
		data.widthHint = 300;
		data.heightHint = 70;
		detailsScroll.setLayoutData(data);
		
		Composite detailsComp = new Composite(detailsScroll, SWT.NONE);
		final GridLayout detailsLayout = new GridLayout();
		detailsLayout.numColumns = 1;
		detailsComp.setLayout(detailsLayout);
		detailsComp.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, true));
		
		descriptionLabel = new Text(detailsComp, SWT.WRAP | SWT.MULTI | SWT.READ_ONLY);
		descriptionLabel.setText("");
		GridData descData = new GridData(GridData.FILL, GridData.CENTER, false, false);
		descriptionLabel.setLayoutData(descData);
		IDEUtil.normalizeBackground(descriptionLabel, detailsComp);
		
		styleLabel = new Text(detailsComp, SWT.WRAP | SWT.MULTI | SWT.READ_ONLY);
		styleLabel.setText("");
		GridData styleData = new GridData(GridData.FILL, GridData.CENTER, false, false);
		styleLabel.setLayoutData(styleData);
		IDEUtil.normalizeBackground(styleLabel, detailsComp);
		
		sourceLabel = new Text(detailsComp, SWT.WRAP | SWT.MULTI | SWT.READ_ONLY);
		sourceLabel.setText("");
		GridData sourceData = new GridData(GridData.FILL, GridData.CENTER, false, false);
		sourceLabel.setLayoutData(sourceData);
		IDEUtil.normalizeBackground(sourceLabel, detailsComp);
		
		// Manage repositories link
		Composite manageReposComp = new Composite(parent, SWT.NONE);
		manageReposComp.setLayout(new GridLayout(2, false));
		manageReposComp.setLayoutData(new GridData(GridData.END, GridData.FILL, false, false, 2, 1));
		
		Label manageRepoLabel = new Label(manageReposComp, SWT.NONE);
		manageRepoLabel.setText(Messages.NewProjectPage_ManageRepoLabel);
		manageRepoLabel.setLayoutData(new GridData(GridData.END, GridData.CENTER, false, false));
		
		Link manageRepoLink = new Link(manageReposComp, SWT.NONE);
		manageRepoLink.setText("<a>" + Messages.NewProjectPage_ManageRepoLink + "</a>");
		manageRepoLink.setToolTipText(Messages.NewProjectPage_ManageRepoTooltip);
		manageRepoLink.setLayoutData(new GridData(GridData.BEGINNING, GridData.CENTER, false, false));

		manageRepoLink.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent event) {
				List<RepositoryInfo> repoList;
				try {
					repoList = TemplateUtil.listTemplateSources(connection.getConid(), new NullProgressMonitor());
					RepositoryManagementDialog repoDialog = new RepositoryManagementDialog(getShell(), connection, repoList);
					if (repoDialog.open() == Window.OK) {
						if (repoDialog.hasChanges()) {
							IRunnableWithProgress runnable = new IRunnableWithProgress() {
								@Override
								public void run(IProgressMonitor monitor) throws InvocationTargetException {
									SubMonitor mon = SubMonitor.convert(monitor, Messages.RepoUpdateTask, 100);
									IStatus status = repoDialog.updateRepos(mon.split(75));
									if (!status.isOK()) {
										throw new InvocationTargetException(status.getException(), status.getMessage());
									}
									if (mon.isCanceled()) {
										return;
									}
									try {
										mon.setTaskName(Messages.NewProjectPage_RefreshTemplatesTask);
										templateList = TemplateUtil.listTemplates(true, connection.getConid(), mon.split(25));
									} catch (Exception e) {
										throw new InvocationTargetException(e, Messages.NewProjectPage_RefreshTemplatesError);
									}
								}
							};
							try {
								getWizard().getContainer().run(true, true, runnable);
							} catch (InvocationTargetException e) {
								MessageDialog.openError(getShell(), Messages.RepoUpdateErrorTitle, e.getMessage());
								return;
							} catch (InterruptedException e) {
								// The user cancelled the operation
								return;
							}
							updateSelectionTable();
						}
					}
				} catch (Exception e) {
					MessageDialog.openError(getShell(), Messages.RepoListErrorTitle, NLS.bind(Messages.RepoListErrorMsg, e));
				}
			}
		});
		
		// Manage registries link
		Composite manageRegistriesComp = new Composite(parent, SWT.NONE);
		manageRegistriesComp.setLayout(new GridLayout(2, false));
		manageRegistriesComp.setLayoutData(new GridData(GridData.END, GridData.FILL, false, false, 2, 1));
		
		Label manageRegistriesLabel = new Label(manageRegistriesComp, SWT.NONE);
		manageRegistriesLabel.setText(Messages.ManageRegistriesLinkLabel);
		manageRegistriesLabel.setLayoutData(new GridData(GridData.END, GridData.CENTER, false, false));
		
		Link manageRegistriesLink = new Link(manageRegistriesComp, SWT.NONE);
		manageRegistriesLink.setText("<a>" + Messages.ManageRegistriesLinkText + "</a>");
		if (connection.isLocal()) {
			manageRegistriesLink.setToolTipText(Messages.ManageRegistriesLinkTooltipLocal);
		} else {
			manageRegistriesLink.setToolTipText(Messages.ManageRegistriesLinkTooltip);
		}
		manageRegistriesLink.setLayoutData(new GridData(GridData.BEGINNING, GridData.CENTER, false, false));

		manageRegistriesLink.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent event) {
				List<RegistryInfo> regList;
				try {
					regList = connection.requestRegistryList();
					RegistryManagementDialog regDialog = new RegistryManagementDialog(getShell(), connection, regList);
					if (regDialog.open() == Window.OK) {
						if (regDialog.hasChanges()) {
							IRunnableWithProgress runnable = new IRunnableWithProgress() {
								@Override
								public void run(IProgressMonitor monitor) throws InvocationTargetException {
									SubMonitor mon = SubMonitor.convert(monitor, Messages.RegUpdateTask, 100);
									IStatus status = regDialog.updateRegistries(mon.split(75));
									if (!status.isOK()) {
										throw new InvocationTargetException(status.getException(), status.getMessage());
									}
									if (mon.isCanceled()) {
										return;
									}
								}
							};
							try {
								getWizard().getContainer().run(true, true, runnable);
							} catch (InvocationTargetException e) {
								MessageDialog.openError(getShell(), Messages.RegUpdateErrorTitle, e.getMessage());
								return;
							} catch (InterruptedException e) {
								// The user cancelled the operation
								return;
							}
						}
					}
				} catch (Exception e) {
					MessageDialog.openError(getShell(), Messages.RegListErrorTitle, NLS.bind(Messages.RegListErrorMsg, e));
				}
			}
		});

		// Listeners
		filterText.addModifyListener(new ModifyListener() {
			@Override
			public void modifyText(ModifyEvent event) {
				updateSelectionTable();
			}
		});

		filterText.addListener(SWT.KeyDown, new Listener() {
			@Override
			public void handleEvent(Event event) {
				if (event.keyCode == SWT.ARROW_DOWN) {
					if (selectionTable.getItemCount() > 0) {
						selectionTable.setSelection(0);
						updateDetails();
						selectionTable.setFocus();
					}
					event.doit = false;
					setPageComplete(validate());
				}
			}
		});

		selectionTable.addListener(SWT.Selection, new Listener() {
			@Override
			public void handleEvent(Event event) {
				updateDetails();
				setPageComplete(validate());
			}
		});
		
		detailsScroll.addListener(SWT.Resize, (event) -> {
			int width = detailsScroll.getClientArea().width;
			descData.widthHint = width - detailsLayout.marginWidth;
			styleData.widthHint = width - detailsLayout.marginWidth;
			sourceData.widthHint = width - detailsLayout.marginWidth;
			Point size = detailsComp.computeSize(SWT.DEFAULT, SWT.DEFAULT);
			detailsScroll.setMinSize(size);
		});
		
		detailsScroll.setContent(detailsComp);
		detailsScroll.setExpandHorizontal(true);
		detailsScroll.setExpandVertical(true);
		detailsScroll.setMinSize(detailsScroll.getContent().computeSize(SWT.DEFAULT, SWT.DEFAULT));
		
		if (selectionTable.getItemCount() > 0) {
			selectionTable.setSelection(0);
		}
		updateDetails();
	}
	
	protected boolean canFinish() {
		return validate();
	}

	private boolean validate() {
		if (!validatePage()) {
			return false;
		}
		if (templateList == null || templateList.isEmpty()) {
			setErrorMessage(Messages.NewProjectPage_EmptyTemplateList);
			return false;
		}
		String projectName = getProjectName();
		if (projectName == null || projectName.isEmpty()) {
			setErrorMessage(Messages.NewProjectPage_EmptyProjectName);
			return false;
		}
		if (!projectNamePattern.matcher(projectName).matches()) {
			setErrorMessage(Messages.NewProjectPage_InvalidProjectName);
			return false;
		}
		if (connection.getAppByName(projectName) != null) {
			setErrorMessage(NLS.bind(Messages.NewProjectPage_ProjectExistsError, projectName));
			return false;
		}
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (project != null && project.exists()) {
			setErrorMessage(NLS.bind(Messages.NewProjectPage_EclipseProjectExistsError, projectName));
			return false;
		}
		IPath location = getLocationPath();
		if (location == null || location.isEmpty()) {
			setErrorMessage(Messages.NewProjectPage_NoLocationError);
			return false;
		}

		// It is an error if the project is located in the codewind-data folder
		IPath dataPath = CoreUtil.getCodewindDataPath();
		if (dataPath != null && dataPath.isPrefixOf(location)) {
			setErrorMessage(NLS.bind(Messages.ProjectLocationInCodewindDataDirError, dataPath.toOSString()));
			return false;
		}
		
		if (selectionTable.getSelectionCount() != 1) {
			setErrorMessage(Messages.NewProjectPage_NoTemplateSelected);
			return false;
		}
		setErrorMessage(null);
		return true;
	}

	public ProjectTemplateInfo getProjectTemplateInfo() {
		if (selectionTable != null) {
			int index = selectionTable.getSelectionIndex();
			if (index >= 0) {
				TableItem item = selectionTable.getItem(index);
				return (ProjectTemplateInfo)item.getData();
			}
		}
		return null;
	}
	
	public CodewindConnection getConnection() {
		return connection;
	}

	private void createItems(Table table, String filter) {
		// Create the items for the table.
		table.removeAll();
		pattern.setPattern("*" + filter + "*");
		for (ProjectTemplateInfo templateInfo : templateList) {
			String template = templateInfo.getLabel();
			String type = ProjectType.getDisplayName(templateInfo.getProjectType());
			String language = ProjectLanguage.getDisplayName(templateInfo.getLanguage());
			String description = templateInfo.getDescription();
			String source = templateInfo.getSource();
			if (pattern.matches(template) || (type != null && pattern.matches(type)) || (language != null && pattern.matches(language)) ||
					(description != null && pattern.matches(description)) || (source != null && pattern.matches(source))) {
				TableItem item = new TableItem(table, SWT.NONE);
				item.setForeground(table.getForeground());
				item.setBackground(table.getBackground());
				item.setText(0, template);
				if (type != null) {
					item.setText(1, type);
				}
				if (language != null) {
					item.setText(2, language);
				}
				item.setData(templateInfo);
			}
		}
	}
	
	public static void sortTable(Table table, TableColumn column) {
		TableItem[] items = table.getItems();
		int rows = items.length;
		int dir = table.getSortDirection() == SWT.DOWN ? 1 : -1;
		TableColumn currentColumn = table.getSortColumn();
		int columnNum = 0;
		for (int j = 0; j < table.getColumnCount(); j++) {
			if (table.getColumn(j).equals(column)) {
				columnNum = j;
				break;
			}
		}
		if (column.equals(currentColumn))
			dir = -dir;
		else
			dir = 1;

		// sort an index map, then move the actual rows
		int[] map = new int[rows];
		for (int i = 0; i < rows; i++)
			map[i] = i;

		for (int i = 0; i < rows - 1; i++) {
			for (int j = i + 1; j < rows; j++) {
				TableItem a = items[map[i]];
				TableItem b = items[map[j]];
				if ((a.getText(columnNum).toLowerCase().compareTo(b.getText(columnNum).toLowerCase()) * dir > 0)) {
					int t = map[i];
					map[i] = map[j];
					map[j] = t;
				}
			}
		}

		// can't move existing items or delete first, so append new items to the end and then delete existing rows
		for (int i = 0; i < rows; i++) {
			int n = map[i];
			TableItem item = new TableItem(table, SWT.NONE);
			for (int j = 0; j < table.getColumnCount(); j++)
				item.setText(j, items[n].getText(j));

			item.setImage(items[n].getImage());
			item.setForeground(items[n].getForeground());
			item.setBackground(items[n].getBackground());
			item.setGrayed(items[n].getGrayed());
			item.setChecked(items[n].getChecked());
			item.setData(items[n].getData());
			items[n].dispose();
		}

		table.setSortDirection(dir == 1 ? SWT.DOWN : SWT.UP);
		table.setSortColumn(column);
	}
	
	private void updateSelectionTable() {
		String text = filterText.getText();
		if (text == null) {
			text = "";
		}
		createItems(selectionTable, text);
		if (selectionTable.getItemCount() > 0)
			selectionTable.select(0);
		updateDetails();
		setPageComplete(validate());
	}
	
	public void updateDetails() {
		// Update the description
		TableItem[] items = selectionTable.getSelection();
		String description = "";
		String style = "";
		String source = "";
		boolean enabled = false;
		if (items.length == 1) {
			enabled = true;
			description = ((ProjectTemplateInfo)items[0].getData()).getDescription();
			if (description == null || description.isEmpty()) {
				description = Messages.NewProjectPage_DetailsNone;
			}
			style = ((ProjectTemplateInfo)items[0].getData()).getProjectStyle();
			if (style == null || style.isEmpty()) {
				style = Messages.NewProjectPage_DetailsNone;
			}
			source = ((ProjectTemplateInfo)items[0].getData()).getSource();
			if (source == null || source.isEmpty()) {
				source = Messages.NewProjectPage_DetailsNone;
			}
		}
		
		descriptionLabel.setText(NLS.bind(Messages.NewProjectPage_DescriptionLabel, description));
		styleLabel.setText(NLS.bind(Messages.NewProjectPage_StyleLabel, style));
		sourceLabel.setText(NLS.bind(Messages.NewProjectPage_SourceLabel, source));
		
		descriptionLabel.setEnabled(enabled);
		styleLabel.setEnabled(enabled);
		sourceLabel.setEnabled(enabled);
		
		resizeEntry(descriptionLabel);
		resizeEntry(styleLabel);
		resizeEntry(sourceLabel);
	}
	
	private void resizeEntry(Text text) {
		// resize label to make scroll bars appear
		int width = text.getParent().getClientArea().width;
		text.setSize(width, text.computeSize(width, SWT.DEFAULT).y);
		
		// resize again if scroll bar added or removed
		int newWidth = text.getParent().getClientArea().width;
		if (newWidth != width) {
			text.setSize(newWidth, text.computeSize(newWidth, SWT.DEFAULT).y);
		}
	}
	
	private void setupConnection() {
		final CodewindManager manager = CodewindManager.getManager();
		connection = CodewindConnectionManager.getLocalConnection();
		if (connection != null && connection.isConnected()) {
			return;
		}
		InstallStatus status = manager.getInstallStatus();
		if (status.isStarted()) {
			connection = CodewindConnectionManager.getLocalConnection();
			return;
		}
		if (!status.isInstalled()) {
			Logger.logError("In NewCodewindProjectPage setupConnection method and Codewind is not installed or has unknown status.");
			connection = null;
			return;
		}
		
		connection = null;
		IRunnableWithProgress runnable = new IRunnableWithProgress() {
			@Override
			public void run(IProgressMonitor monitor) throws InvocationTargetException {
				try {
					ProcessResult result = InstallUtil.startCodewind(status.getVersion(), monitor);
					if (result.getExitValue() != 0) {
						Logger.logError("Installer start failed with return code: " + result.getExitValue() + ", output: " + result.getOutput() + ", error: " + result.getError()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
						String errorText = result.getError() != null && !result.getError().isEmpty() ? result.getError() : result.getOutput();
						throw new InvocationTargetException(null, "There was a problem while trying to start Codewind: " + errorText); //$NON-NLS-1$
					}
					connection = CodewindConnectionManager.getLocalConnection();
					ViewHelper.refreshCodewindExplorerView(connection);
				} catch (IOException e) {
					throw new InvocationTargetException(e, "An error occurred trying to start Codewind: " + e.getMessage()); //$NON-NLS-1$
				} catch (TimeoutException e) {
					throw new InvocationTargetException(e, "Codewind did not start in the expected time: " + e.getMessage()); //$NON-NLS-1$
				}
			}
		};
		try {
			getWizard().getContainer().run(true, true, runnable);
		} catch (InvocationTargetException e) {
			Logger.logError("An error occurred trying to start Codewind", e); //$NON-NLS-1$
			return;
		} catch (InterruptedException e) {
			Logger.logError("Codewind start was interrupted", e); //$NON-NLS-1$
			return;
		}
	}
}
