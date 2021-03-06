package com.logicaldoc.gui.frontend.client.impex;

import com.logicaldoc.gui.common.client.Feature;
import com.logicaldoc.gui.common.client.Menu;
import com.logicaldoc.gui.common.client.Session;
import com.logicaldoc.gui.common.client.i18n.I18N;
import com.logicaldoc.gui.frontend.client.administration.AdminScreen;
import com.logicaldoc.gui.frontend.client.impex.archives.ExportArchivesPanel;
import com.logicaldoc.gui.frontend.client.impex.archives.ImportArchivesPanel;
import com.logicaldoc.gui.frontend.client.impex.converters.FormatConvertersPanel;
import com.logicaldoc.gui.frontend.client.impex.email.EmailAccountsPanel;
import com.logicaldoc.gui.frontend.client.impex.folders.ImportFoldersPanel;
import com.logicaldoc.gui.frontend.client.impex.syndication.SyndicationsPanel;
import com.smartgwt.client.types.Overflow;
import com.smartgwt.client.widgets.Button;
import com.smartgwt.client.widgets.events.ClickEvent;
import com.smartgwt.client.widgets.events.ClickHandler;
import com.smartgwt.client.widgets.layout.VLayout;

/**
 * This panel shows the administration of import/export features.
 * 
 * @author Matteo Caruso - LogicalDOC
 * @since 6.0
 */
public class ImpexMenu extends VLayout {

	public ImpexMenu() {
		setMargin(10);
		setMembersMargin(5);
		setOverflow(Overflow.AUTO);

		Button importFolders = new Button(I18N.message("importfolders"));
		importFolders.setWidth100();
		importFolders.setHeight(25);
		if (Feature.visible(Feature.IMPORT_REMOTE_FOLDERS) || Feature.visible(Feature.IMPORT_LOCAL_FOLDERS)) {
			addMember(importFolders);
			if (!Feature.enabled(Feature.IMPORT_REMOTE_FOLDERS) && !Feature.enabled(Feature.IMPORT_LOCAL_FOLDERS)) {
				importFolders.setDisabled(true);
				importFolders.setTooltip(I18N.message("featuredisabled"));
			}
		}
		importFolders.addClickHandler(new ClickHandler() {
			@Override
			public void onClick(ClickEvent event) {
				AdminScreen.get().setContent(new ImportFoldersPanel());
			}
		});

		Button emails = new Button(I18N.message("emailaccounts"));
		emails.setWidth100();
		emails.setHeight(25);
		if (Feature.visible(Feature.EMAIL_IMPORT)) {
			addMember(emails);
			if (!Feature.enabled(Feature.EMAIL_IMPORT)) {
				emails.setDisabled(true);
				emails.setTooltip(I18N.message("featuredisabled"));
			}
		}
		emails.addClickHandler(new ClickHandler() {
			@Override
			public void onClick(ClickEvent event) {
				AdminScreen.get().setContent(new EmailAccountsPanel());
			}
		});

		Button importArchives = new Button(I18N.message("importarchives"));
		importArchives.setWidth100();
		importArchives.setHeight(25);
		if (Feature.visible(Feature.IMPEX)) {
			addMember(importArchives);
			if (!Feature.enabled(Feature.IMPEX)) {
				importArchives.setDisabled(true);
				importArchives.setTooltip(I18N.message("featuredisabled"));
			}
		}
		importArchives.addClickHandler(new ClickHandler() {
			@Override
			public void onClick(ClickEvent event) {
				AdminScreen.get().setContent(new ImportArchivesPanel());
			}
		});

		Button exportArchives = new Button(I18N.message("exportarchives"));
		exportArchives.setWidth100();
		exportArchives.setHeight(25);

		if (Feature.visible(Feature.IMPEX)) {
			addMember(exportArchives);
			if (!Feature.enabled(Feature.IMPEX)) {
				exportArchives.setDisabled(true);
				exportArchives.setTooltip(I18N.message("featuredisabled"));
			}
		}
		exportArchives.addClickHandler(new ClickHandler() {
			@Override
			public void onClick(ClickEvent event) {
				AdminScreen.get().setContent(new ExportArchivesPanel());
			}
		});

		Button converters = new Button(I18N.message("formatconverters"));
		converters.setWidth100();
		converters.setHeight(25);

		if (Session.get().isDefaultTenant() && Feature.visible(Feature.FORMAT_CONVERSION)
				&& Menu.enabled(Menu.FORMAT_CONVERTERS)) {
			addMember(converters);
			if (!Feature.enabled(Feature.FORMAT_CONVERSION)) {
				converters.setDisabled(true);
				converters.setTooltip(I18N.message("featuredisabled"));
			}
		}

		converters.addClickHandler(new ClickHandler() {
			@Override
			public void onClick(ClickEvent event) {
				AdminScreen.get().setContent(new FormatConvertersPanel());
			}
		});

		Button syndication = new Button(I18N.message("syndication"));
		syndication.setWidth100();
		syndication.setHeight(25);

		if (Feature.visible(Feature.SYNDICATION) && Menu.enabled(Menu.SYNDICATION)) {
			addMember(syndication);
			if (!Feature.enabled(Feature.SYNDICATION)) {
				syndication.setDisabled(true);
				syndication.setTooltip(I18N.message("featuredisabled"));
			}
		}
		syndication.addClickHandler(new ClickHandler() {
			@Override
			public void onClick(ClickEvent event) {
				AdminScreen.get().setContent(new SyndicationsPanel());
			}
		});
	}
}
