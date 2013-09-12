package com.muzima.view.forms;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.TextView;
import android.widget.Toast;
import com.actionbarsherlock.view.ActionMode;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;
import com.muzima.MuzimaApplication;
import com.muzima.R;
import com.muzima.adapters.forms.AllAvailableFormsAdapter;
import com.muzima.controller.FormController;
import com.muzima.listeners.DownloadListener;
import com.muzima.search.api.util.StringUtil;
import com.muzima.tasks.DownloadMuzimaTask;
import com.muzima.tasks.forms.DownloadFormTemplateTask;
import com.muzima.utils.Constants;
import com.muzima.utils.DateUtils;
import com.muzima.utils.NetworkUtils;

import java.util.Date;
import java.util.List;

import static android.os.AsyncTask.Status.PENDING;
import static android.os.AsyncTask.Status.RUNNING;

public class AllAvailableFormsListFragment extends FormsListFragment implements DownloadListener<Integer[]>{
    private static final String TAG = "AllAvailableFormsListFragment";

    public static final String FORMS_METADATA_LAST_SYNCED_TIME = "formsMetadataSyncedTime";
    public static final long NOT_SYNCED_TIME = -1;

    private ActionMode actionMode;
    private boolean actionModeActive = false;
    private DownloadFormTemplateTask formTemplateDownloadTask;
    private OnTemplateDownloadComplete templateDownloadCompleteListener;
    private TextView syncText;
    private boolean newFormsSyncInProgress;

    public static AllAvailableFormsListFragment newInstance(FormController formController) {
        AllAvailableFormsListFragment f = new AllAvailableFormsListFragment();
        f.formController = formController;
        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (listAdapter == null) {
            listAdapter = new AllAvailableFormsAdapter(getActivity(), R.layout.item_forms_list, formController);
        }
        noDataMsg = getActivity().getResources().getString(R.string.no_new_form_msg);
        noDataTip = getActivity().getResources().getString(R.string.no_new_form_tip);

        // this can happen on orientation change
        if (actionModeActive) {
            actionMode = getSherlockActivity().startActionMode(new NewFormsActionModeCallback());
            actionMode.setTitle(String.valueOf(((AllAvailableFormsAdapter) listAdapter).getSelectedForms().size()));
        }
        super.onCreate(savedInstanceState);
    }

    @Override
    protected View setupMainView(LayoutInflater inflater, ViewGroup container) {
        View view = inflater.inflate(R.layout.layout_synced_list, container, false);
        syncText = (TextView) view.findViewById(R.id.sync_text);
        updateSyncText();
        return view;
    }

    @Override
    public void onDestroy() {
        if(formTemplateDownloadTask != null){
            formTemplateDownloadTask.cancel(false);
        }
        super.onDestroy();
    }

    @Override
    public void onItemClick(AdapterView<?> adapterView, View view, int position, long id) {
        if (!actionModeActive) {
            actionMode = getSherlockActivity().startActionMode(new NewFormsActionModeCallback());
            actionModeActive = true;
        }
        ((AllAvailableFormsAdapter) listAdapter).onListItemClick(position);
        int numOfSelectedForms = ((AllAvailableFormsAdapter) listAdapter).getSelectedForms().size();
        if (numOfSelectedForms == 0 && actionModeActive) {
            actionMode.finish();
        }
        actionMode.setTitle(String.valueOf(numOfSelectedForms));
    }

    @Override
    public void downloadTaskComplete(Integer[] result) {
        if (templateDownloadCompleteListener != null) {
            templateDownloadCompleteListener.onTemplateDownloadComplete(result);
        }
        listAdapter.reloadData();
    }

    @Override
    public void downloadTaskStart() {
    }

    @Override
    public void synchronizationComplete(Integer[] status) {
        newFormsSyncInProgress = false;

        ((FormsActivity)getActivity()).hideProgressbar();
        if(status[0] == DownloadMuzimaTask.SUCCESS){
            updateSyncText();
        }
        super.synchronizationComplete(status);
    }

    @Override
    public void synchronizationStarted() {
        newFormsSyncInProgress = true;
    }

    public final class NewFormsActionModeCallback implements ActionMode.Callback {

        @Override
        public boolean onCreateActionMode(ActionMode actionMode, Menu menu) {
            getSherlockActivity().getSupportMenuInflater().inflate(R.menu.actionmode_menu_download, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode actionMode, MenuItem menuItem) {
            switch (menuItem.getItemId()) {
                case R.id.menu_download:
                    if(newFormsSyncInProgress){
                        Toast.makeText(getActivity(), "Action not allowed while sync is in progress", Toast.LENGTH_SHORT).show();
                        if (AllAvailableFormsListFragment.this.actionMode != null) {
                            AllAvailableFormsListFragment.this.actionMode.finish();
                        }
                        break;
                    }

                    if(!NetworkUtils.isConnectedToNetwork(getActivity())){
                        Toast.makeText(getActivity(), "No connection found, please connect your device and try again", Toast.LENGTH_SHORT).show();
                        return true;
                    }

                    if (formTemplateDownloadTask != null &&
                            (formTemplateDownloadTask.getStatus() == PENDING || formTemplateDownloadTask.getStatus() == RUNNING)) {
                        Toast.makeText(getActivity(), "Already fetching form templates, ignored the request", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getActivity().getBaseContext());
                    formTemplateDownloadTask = new DownloadFormTemplateTask((MuzimaApplication) getActivity().getApplication());
                    formTemplateDownloadTask.addDownloadListener(AllAvailableFormsListFragment.this);
                    String usernameKey = getResources().getString(R.string.preference_username);
                    String passwordKey = getResources().getString(R.string.preference_password);
                    String serverKey = getResources().getString(R.string.preference_server);
                    String[] credentials = new String[]{settings.getString(usernameKey, StringUtil.EMPTY),
                            settings.getString(passwordKey, StringUtil.EMPTY),
                            settings.getString(serverKey, StringUtil.EMPTY)};
                    ((FormsActivity)getActivity()).showProgressBar();
                    formTemplateDownloadTask.execute(credentials, getSelectedFormsArray());
                    if (AllAvailableFormsListFragment.this.actionMode != null) {
                        AllAvailableFormsListFragment.this.actionMode.finish();
                    }
                    return true;
            }
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode actionMode) {
            actionModeActive = false;
            ((AllAvailableFormsAdapter) listAdapter).clearSelectedForms();
        }
    }

    public void setTemplateDownloadCompleteListener(OnTemplateDownloadComplete templateDownloadCompleteListener) {
        this.templateDownloadCompleteListener = templateDownloadCompleteListener;
    }

    public interface OnTemplateDownloadComplete {
        public void onTemplateDownloadComplete(Integer[] result);
    }

    private String[] getSelectedFormsArray() {
        List<String> selectedForms = ((AllAvailableFormsAdapter) listAdapter).getSelectedForms();
        String[] selectedFormUuids = new String[selectedForms.size()];
        return selectedForms.toArray(selectedFormUuids);
    }

    private void updateSyncText() {
        SharedPreferences pref = getActivity().getSharedPreferences(Constants.SYNC_PREF, Context.MODE_PRIVATE);
        long lastSyncedTime = pref.getLong(FORMS_METADATA_LAST_SYNCED_TIME, NOT_SYNCED_TIME);
        String lastSyncedMsg = "Not synced yet";
        if(lastSyncedTime != NOT_SYNCED_TIME){
            lastSyncedMsg = "Last synced on: " + DateUtils.getFormattedDateTime(new Date(lastSyncedTime));
        }
        syncText.setText(lastSyncedMsg);
    }
}
