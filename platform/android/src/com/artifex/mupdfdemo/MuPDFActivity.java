// compile-command: cd ~/src/android/mupdf/platform/android && ant clean && ~/src/android/android-ndk-r9/ndk-build && ant debug && cp bin/MuPDF-debug.apk /home/cgogolin/Dropbox/galaxynote8/ 

package com.artifex.mupdfdemo;

import java.io.InputStream;
import java.io.File;
import java.util.concurrent.Executor;

import java.util.Set;

import com.artifex.mupdfdemo.ReaderView.ViewMapper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuInflater;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.WindowManager;
import android.widget.Toast;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ViewAnimator;
import android.widget.SearchView;
import android.widget.ShareActionProvider;
import android.preference.PreferenceManager;
import android.app.ActionBar;
import android.app.SearchManager;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;


import android.text.InputType;

class ThreadPerTaskExecutor implements Executor {
    public void execute(Runnable r) {
        new Thread(r).start();
    }
}

public class MuPDFActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener, SearchView.OnQueryTextListener, SearchView.OnCloseListener, FilePicker.FilePickerSupport
{       
        /* The core rendering instance */
    enum ActionBarMode {Main, Annot, Edit, Search, Copy, Selection};
    enum AcceptMode {Highlight, Underline, StrikeOut, Ink, CopyText};
    
    private SearchView searchView = null;
    private String oldQueryText = "";
    private String mQuery = "";
    private int mCurrentSearchResultOnPage = 0;
    private ShareActionProvider mShareActionProvider = null;
    private boolean mNotSaveOnDestroyThisTime = false;
    private boolean mNotSaveOnPauseThisTime = false;
    private int mPageBeforeInternalLinkHit = -1;
    private float mNormalizedScaleBeforeInternalLinkHit = 1.0f;
    private float mNormalizedXScrollBeforeInternalLinkHit = 0;
    private float mNormalizedYScrollBeforeInternalLinkHit = 0;
    private boolean mCanUndo = false;

    private final int    OUTLINE_REQUEST=0;
    private final int    PRINT_REQUEST=1;
    private final int    FILEPICK_REQUEST = 2;
    private final int    SAVEAS_REQUEST=3;
    private MuPDFCore    core;
    private MuPDFReaderView mDocView;
    private EditText     mPasswordView;
    private ActionBarMode   mActionBarMode = ActionBarMode.Main;
    private AcceptMode   mAcceptMode = AcceptMode.Highlight;
    private SearchTask   mSearchTask;
    private AlertDialog.Builder mAlertBuilder;
    private boolean    mLinkHighlight = false;
    private final Handler mHandler = new Handler();
    private boolean mAlertsActive= false;
    private boolean mReflow = false;
    private AsyncTask<Void,Void,MuPDFAlert> mAlertTask;
    private AlertDialog mAlertDialog;
    private FilePicker mFilePicker;

    public void createAlertWaiter() {
        mAlertsActive = true;
            // All mupdf library calls are performed on asynchronous tasks to avoid stalling
            // the UI. Some calls can lead to javascript-invoked requests to display an
            // alert dialog and collect a reply from the user. The task has to be blocked
            // until the user's reply is received. This method creates an asynchronous task,
            // the purpose of which is to wait of these requests and produce the dialog
            // in response, while leaving the core blocked. When the dialog receives the
            // user's response, it is sent to the core via replyToAlert, unblocking it.
            // Another alert-waiting task is then created to pick up the next alert.
        if (mAlertTask != null) {
            mAlertTask.cancel(true);
            mAlertTask = null;
        }
        if (mAlertDialog != null) {
            mAlertDialog.cancel();
            mAlertDialog = null;
        }
        mAlertTask = new AsyncTask<Void,Void,MuPDFAlert>() {

            @Override
            protected MuPDFAlert doInBackground(Void... arg0) {
                if (!mAlertsActive)
                    return null;

                return core.waitForAlert();
            }

            @Override
            protected void onPostExecute(final MuPDFAlert result) {
                    // core.waitForAlert may return null when shutting down
                if (result == null)
                    return;
                final MuPDFAlert.ButtonPressed pressed[] = new MuPDFAlert.ButtonPressed[3];
                for(int i = 0; i < 3; i++)
                    pressed[i] = MuPDFAlert.ButtonPressed.None;
                DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            mAlertDialog = null;
                            if (mAlertsActive) {
                                int index = 0;
                                switch (which) {
                                    case AlertDialog.BUTTON1: index=0; break;
                                    case AlertDialog.BUTTON2: index=1; break;
                                    case AlertDialog.BUTTON3: index=2; break;
                                }
                                result.buttonPressed = pressed[index];
                                    // Send the user's response to the core, so that it can
                                    // continue processing.
                                core.replyToAlert(result);
                                    // Create another alert-waiter to pick up the next alert.
                                createAlertWaiter();
                            }
                        }
                    };
                mAlertDialog = mAlertBuilder.create();
                mAlertDialog.setTitle(result.title);
                mAlertDialog.setMessage(result.message);
                switch (result.iconType)
                {
                    case Error:
                        break;
                    case Warning:
                        break;
                    case Question:
                        break;
                    case Status:
                        break;
                }
                switch (result.buttonGroupType)
                {
                    case OkCancel:
                    mAlertDialog.setButton(AlertDialog.BUTTON2, getString(R.string.cancel), listener);
                    pressed[1] = MuPDFAlert.ButtonPressed.Cancel;
                    case Ok:
                    mAlertDialog.setButton(AlertDialog.BUTTON1, getString(R.string.okay), listener);
                    pressed[0] = MuPDFAlert.ButtonPressed.Ok;
                    break;
                    case YesNoCancel:
                    mAlertDialog.setButton(AlertDialog.BUTTON3, getString(R.string.cancel), listener);
                    pressed[2] = MuPDFAlert.ButtonPressed.Cancel;
                    case YesNo:
                    mAlertDialog.setButton(AlertDialog.BUTTON1, getString(R.string.yes), listener);
                    pressed[0] = MuPDFAlert.ButtonPressed.Yes;
                    mAlertDialog.setButton(AlertDialog.BUTTON2, getString(R.string.no), listener);
                    pressed[1] = MuPDFAlert.ButtonPressed.No;
                    break;
                }
                mAlertDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                        public void onCancel(DialogInterface dialog) {
                            mAlertDialog = null;
                            if (mAlertsActive) {
                                result.buttonPressed = MuPDFAlert.ButtonPressed.None;
                                core.replyToAlert(result);
                                createAlertWaiter();
                            }
                        }
                    });

                mAlertDialog.show();
            }
        };

        mAlertTask.executeOnExecutor(new ThreadPerTaskExecutor());
    }

    public void destroyAlertWaiter() {
        mAlertsActive = false;
        if (mAlertDialog != null) {
            mAlertDialog.cancel();
            mAlertDialog = null;
        }
        if (mAlertTask != null) {
            mAlertTask.cancel(true);
            mAlertTask = null;
        }
    }

    private MuPDFCore openFile(String path)
	{
            System.out.println("Trying to open "+path);
            try
            {
                core = new MuPDFCore(this, path);
                    // New file: drop the old outline data
                OutlineActivityData.set(null);
            }
            catch (Exception e)
            {
                System.out.println(e);
                return null;
            }
            return core;
	}

    private MuPDFCore openBuffer(byte buffer[], String displayName)
	{
            System.out.println("Trying to open byte buffer");
            try
            {
                core = new MuPDFCore(this, buffer, displayName);
                    // New file: drop the old outline data
                OutlineActivityData.set(null);
            }
            catch (Exception e)
            {
                System.out.println(e);
                return null;
            }
            return core;
	}


    // @Override
    // public void onNewIntent(Intent intent)
    //     {}

    
    @Override
    public void onCreate(Bundle savedInstanceState)
        {
            super.onCreate(savedInstanceState);
            
                //Set default preferences on first start
            PreferenceManager.setDefaultValues(this, SettingsActivity.SHARED_PREFERENCES_STRING, MODE_MULTI_PROCESS, R.xml.preferences, false);
            
            getSharedPreferences(SettingsActivity.SHARED_PREFERENCES_STRING, MODE_MULTI_PROCESS).registerOnSharedPreferenceChangeListener(this);
            onSharedPreferenceChanged(getSharedPreferences(SettingsActivity.SHARED_PREFERENCES_STRING, MODE_MULTI_PROCESS),""); //Call this once so I don't need to duplicate code
            
                //Get the ActionBarMode, AcceptMode and PageBeforeInternalLinkHit from the bundle
            if(savedInstanceState != null)
            {
                    //We don't want to do this at the moment because we can't save what was selected ar drawn so easily 
                    // mActionBarMode = ActionBarMode.valueOf(savedInstanceState.getString("ActionBarMode", ActionBarMode.Main.toString ()));
                    // mAcceptMode = AcceptMode.valueOf(savedInstanceState.getString("AcceptMode", AcceptMode.Highlight.toString ()));
                mPageBeforeInternalLinkHit = savedInstanceState.getInt("PageBeforeInternalLinkHit", mPageBeforeInternalLinkHit);
                mNormalizedScaleBeforeInternalLinkHit = savedInstanceState.getFloat("NormalizedScaleBeforeInternalLinkHit", mNormalizedScaleBeforeInternalLinkHit);
                mNormalizedXScrollBeforeInternalLinkHit = savedInstanceState.getFloat("NormalizedXScrollBeforeInternalLinkHit", mNormalizedXScrollBeforeInternalLinkHit);
                mNormalizedYScrollBeforeInternalLinkHit = savedInstanceState.getFloat("NormalizedYScrollBeforeInternalLinkHit", mNormalizedYScrollBeforeInternalLinkHit);
            }
            
                //Initialize the alert builder
            mAlertBuilder = new AlertDialog.Builder(this);
            
                //Get the core saved with onRetainNonConfigurationInstance()
            if (core == null) {
            core = (MuPDFCore)getLastNonConfigurationInstance();
            }
        }


    // @Override
    // protected void onStart() {
    // super.onStart();
    // }

    
    @Override
    protected void onResume()
        {
            super.onResume();

            mNotSaveOnDestroyThisTime = false;
            mNotSaveOnPauseThisTime = false;
            
                //If core was not restored during onCreat()
                //or is not still present because the app was
                //only paused and save on pause is off set it up now
            if (core == null) setupCore();
            if (core != null) //OK, so apparently we have a valid pdf open
            {
                SearchTaskResult.set(null);
                createAlertWaiter();
                core.startAlerts();
                core.onSharedPreferenceChanged(getSharedPreferences(SettingsActivity.SHARED_PREFERENCES_STRING, MODE_MULTI_PROCESS),"");
                setupUI();
            }
            else //Something went wrong
            {
                AlertDialog alert = mAlertBuilder.create();
                alert.setTitle(R.string.cannot_open_document);
                alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dismiss),
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {
                                        finish();
                                    }
                                });
                alert.show();
            }
        }


        @Override
        protected void onPause() {
            super.onPause();
            
            if (mSearchTask != null) mSearchTask.stop();
            
            if(mDocView != null)
            {
                mDocView.applyToChildren(new ReaderView.ViewMapper() {
                        void applyToView(View view) {
                            ((MuPDFView)view).releaseBitmaps();
                        }
                    });
            }
            
            if (core != null && mDocView != null) {

                String path = core.getPath();
                SharedPreferences prefs = getSharedPreferences(SettingsActivity.SHARED_PREFERENCES_STRING, Context.MODE_MULTI_PROCESS);
                SharedPreferences.Editor edit = prefs.edit();
                if(path != null)
                {
                        //Read the recent files list from preferences
                    RecentFilesList recentFilesList = new RecentFilesList(prefs);                    
                        //Add the current file
                    recentFilesList.push(path);
                        //Write the recent files list
                    recentFilesList.write(edit);
                }
                    //Save the current viewport
                if(path != null)
                    saveViewport(edit, path);
                else
                    saveViewport(edit, core.getFileName());
            }
            
            if (core != null)
            {
                destroyAlertWaiter();
                core.stopAlerts();
            }
            
            if(core != null && !isChangingConfigurations())
            {
                SharedPreferences sharedPref = getSharedPreferences(SettingsActivity.SHARED_PREFERENCES_STRING, MODE_MULTI_PROCESS);
                if(!mNotSaveOnPauseThisTime && core.hasChanges() && core.getFileName() != null && sharedPref.getBoolean(SettingsActivity.PREF_SAVE_ON_PAUSE, true))
                {
                    boolean success = core.save();
                    if(!success) showInfo(getString(R.string.error_saveing));
                    core.onDestroy(); //Destroy only if we have saved
                    core = null;
                }
            }
        }
    

    // @Override
    // protected void onStop() {
    //     super.onStop():
    // }

    @Override
    protected void onDestroy() {//There is no guarantee that this is ever called!!!
	
        super.onDestroy();
            
            getSharedPreferences(SettingsActivity.SHARED_PREFERENCES_STRING, MODE_MULTI_PROCESS).unregisterOnSharedPreferenceChangeListener(this);

            if(core != null && !isChangingConfigurations())
            {
                SharedPreferences sharedPref = getSharedPreferences(SettingsActivity.SHARED_PREFERENCES_STRING, MODE_MULTI_PROCESS);
                if(!mNotSaveOnDestroyThisTime && core.hasChanges() && core.getFileName() != null && sharedPref.getBoolean(SettingsActivity.PREF_SAVE_ON_DESTROY, true))
                {
                    boolean success = core.save();
                    if(!success) showInfo(getString(R.string.error_saveing));
                }
                core.onDestroy(); //Destroy in any case
                core = null;
            }
            
            if (mAlertTask != null) {
                mAlertTask.cancel(true);
                mAlertTask = null;
            }
            searchView = null;
            mShareActionProvider = null;
            super.onDestroy();
	}
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) //Inflates the options menu
        {
            super.onCreateOptionsMenu(menu);
            
            MenuInflater inflater = getMenuInflater();
            switch (mActionBarMode)
            {
                case Main:
                    inflater.inflate(R.menu.main_menu, menu);

                        // Set up the back before link clicked icon
                    MenuItem linkBackItem = menu.findItem(R.id.menu_linkback);
                    if (mPageBeforeInternalLinkHit == -1) linkBackItem.setEnabled(false).setVisible(false);
                    
                        // Set up the share action
                    MenuItem shareItem = menu.findItem(R.id.menu_share);
                    if (core == null || core.getPath() == null)
                    {
                        shareItem.setEnabled(false).setVisible(false);
                    }
                    else
                    {
                        if (mShareActionProvider == null)
                        {
                            mShareActionProvider = (ShareActionProvider) shareItem.getActionProvider();
                            Intent shareIntent = new Intent();
                            shareIntent.setAction(Intent.ACTION_SEND);
                            shareIntent.setType("plain/text");
                            shareIntent.setType("*/*");
                            shareIntent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(new File(Uri.parse(core.getPath()).getPath())));
                            if (mShareActionProvider != null) mShareActionProvider.setShareIntent(shareIntent);
                        }   
                    }
                    break;
                case Selection:
                    inflater.inflate(R.menu.selection_menu, menu);
                    break;
                case Annot:
                case Edit:
                case Copy:
                    inflater.inflate(R.menu.annot_menu, menu);
                    MenuItem undoButton = menu.findItem(R.id.menu_undo);
                    if (!mCanUndo) undoButton.setEnabled(false).setVisible(false);
                    break;
                case Search:
                    inflater.inflate(R.menu.search_menu, menu);
                        // Associate searchable configuration with the SearchView
                    SearchManager searchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
                    searchView = (SearchView) menu.findItem(R.id.menu_search_box).getActionView();
                    searchView.setSearchableInfo(searchManager.getSearchableInfo(getComponentName()));
                    searchView.setIconified(false);
                    searchView.setOnCloseListener(this); //Implemented in: public void onClose(View view)
                    searchView.setOnQueryTextListener(this); //Implemented in: public boolean onQueryTextChange(String query) and public boolean onQueryTextSubmit(String query)
                default:
            }
            return true;
        }

    @Override
    public boolean onClose() {//X button in search box
        SearchTaskResult.set(null);
            // Make the ReaderView act on the change to mSearchTaskResult
            // via overridden onChildSetup method.
        mDocView.resetupChildren();
        return false;
    }

    
    @Override
    public boolean onQueryTextChange(String query) {//For search
        mQuery = query;
            //This is a hacky way to determine when the user has reset the text field with the X button 
        if ( query.length() == 0 && oldQueryText.length() > 1) {
            SearchTaskResult.set(null);
            mDocView.resetupChildren();
        }
        oldQueryText = query;
        return false;
    }

    @Override 
    public boolean onQueryTextSubmit(String query) {//For search
        // if(mQuery != query)
        // {
        mQuery = query;
        search(1);
        // }
        mDocView.requestFocus();
        hideKeyboard();
        return true; //We handle this here and don't want to call onNewIntent()
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) { //Handel clicks in the options menu 
        MuPDFView pageView = (MuPDFView) mDocView.getDisplayedView();
        switch (item.getItemId()) 
        {
            case R.id.menu_undo:
                pageView.undoDraw();
                mDocView.onNumberOfStrokesChanged(pageView.getDrawingSize());
                return true;
            case R.id.menu_addpage:
                    //Insert a new blank page at the end
                if(core!=null) core.insertBlankPageAtEnd();
                onPause();
                if(core!=null)
                {
                    boolean success = core.save();
                    if(!success) showInfo(getString(R.string.error_saveing));
                    core.onDestroy();
                    core = null;
                }
                onResume();
                    //Switch to the newly inserted page
                mDocView.setDisplayedViewIndex(core.countPages()-1);
                return true;                
            case R.id.menu_settings:
                Intent intent = new Intent(this,SettingsActivity.class);
                startActivity(intent);
                return true;
            case R.id.menu_draw:
                mAcceptMode = AcceptMode.Ink;
                mDocView.setMode(MuPDFReaderView.Mode.Drawing);
                mActionBarMode = ActionBarMode.Annot;
                invalidateOptionsMenu();
                return true;
            case R.id.menu_highlight:
                    // mAcceptMode = AcceptMode.Highlight;
                    // mDocView.setMode(MuPDFReaderView.Mode.Selecting);
                    // mActionBarMode = ActionBarMode.Annot;
                if (pageView.hasSelection()) {
                    pageView.markupSelection(Annotation.Type.HIGHLIGHT);
                    mDocView.setMode(MuPDFReaderView.Mode.Viewing);
                    mActionBarMode = ActionBarMode.Main;
                    invalidateOptionsMenu();
                }
                else
                    showInfo(getString(R.string.select_text));
                return true;
            case R.id.menu_underline:
                    // mAcceptMode = AcceptMode.Underline;
                    // mDocView.setMode(MuPDFReaderView.Mode.Selecting);
                    // mActionBarMode = ActionBarMode.Annot;
                if (pageView.hasSelection()) {
                    pageView.markupSelection(Annotation.Type.UNDERLINE);
                    mDocView.setMode(MuPDFReaderView.Mode.Viewing);
                    mActionBarMode = ActionBarMode.Main;
                    invalidateOptionsMenu();
                }
                else
                    showInfo(getString(R.string.select_text));
                return true;
            case R.id.menu_strikeout:
                    // mAcceptMode = AcceptMode.StrikeOut;
                    // mDocView.setMode(MuPDFReaderView.Mode.Selecting);
                    // mActionBarMode = ActionBarMode.Annot;
                if (pageView.hasSelection()) {
                    pageView.markupSelection(Annotation.Type.STRIKEOUT);
                    mDocView.setMode(MuPDFReaderView.Mode.Viewing);
                    mActionBarMode = ActionBarMode.Main;
                    invalidateOptionsMenu();
                }
                else
                    showInfo(getString(R.string.select_text));
                return true;
            case R.id.menu_copytext:
                    // mActionBarMode = ActionBarMode.Copy;
                    // invalidateOptionsMenu();
                    // mAcceptMode = AcceptMode.CopyText;
                    // mDocView.setMode(MuPDFReaderView.Mode.Selecting);
                    // showInfo(getString(R.string.select_text));
                if (pageView.hasSelection()) {
                    boolean success = pageView.copySelection();
                    showInfo(success?getString(R.string.copied_to_clipboard):getString(R.string.no_text_selected));
                    mDocView.setMode(MuPDFReaderView.Mode.Viewing);
                    mActionBarMode = ActionBarMode.Main;
                    invalidateOptionsMenu();
                }
                else
                    showInfo(getString(R.string.select_text));
                return true;
            case R.id.menu_cancel:
                switch (mActionBarMode) {
                    case Annot:
                    case Copy:
                        if (pageView != null) {
                                pageView.deselectText();
                                pageView.cancelDraw();
                        }
                        mCanUndo = false;
                        mDocView.setMode(MuPDFReaderView.Mode.Viewing);
                        break;
                    case Edit:
                        if (pageView != null)
                            pageView.deleteSelectedAnnotation();
                        break;
                    case Search:
                        hideKeyboard();
                        SearchTaskResult.set(null);
                        mDocView.resetupChildren();
                        break;
                    case Selection:
                        mDocView.setMode(MuPDFReaderView.Mode.Viewing);
                        pageView.deselectText();
                        break;
                }
                mActionBarMode = ActionBarMode.Main;
                invalidateOptionsMenu();
                return true;
            case R.id.menu_accept:
                switch (mActionBarMode) {
                    case Annot:
                    case Copy:
                        if (pageView != null) {
                            switch (mAcceptMode) {
                                case Ink:
                                    pageView.saveDraw();
                                    break;
                                        // case Highlight:
                                        //     pageView.markupSelection(Annotation.Type.HIGHLIGHT);
                                        //     break;
                                        // case Underline:
                                        //     pageView.markupSelection(Annotation.Type.UNDERLINE);
                                        //     break;
                                        // case StrikeOut:
                                        //     pageView.markupSelection(Annotation.Type.STRIKEOUT);
                                        //     break;
                                        // case CopyText:    
                                        //     boolean success = pageView.copySelection();
                                        //     showInfo(success?getString(R.string.copied_to_clipboard):getString(R.string.no_text_selected));
                                        //     break;
                            }
                        }
                        mCanUndo = false;
                        mDocView.setMode(MuPDFReaderView.Mode.Viewing);
                        break;
                    case Edit:
                        if (pageView != null)
                            pageView.deselectAnnotation();
                }
                mActionBarMode = ActionBarMode.Main;
                invalidateOptionsMenu();
                return true;
            case R.id.menu_print:
                printDoc();
                return true;
            case R.id.menu_search:
                mActionBarMode = ActionBarMode.Search;
                invalidateOptionsMenu();
                return true;
            case R.id.menu_next:
                if (mQuery != "")
                {
                    hideKeyboard();
                    search(1);
                }
                return true;
            case R.id.menu_previous:
                if (mQuery != "")
                {
                    hideKeyboard();
                    search(-1);
                }
                return true;
            case R.id.menu_save:
                DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            if (which == AlertDialog.BUTTON_POSITIVE) {
                                onPause();
                                    //If we have not saved during on pause do it now
                                if(core != null)
                                {
                                    boolean success = core.save();
                                    if(!success) showInfo(getString(R.string.error_saveing));
                                    core.onDestroy(); //Destroy only if we have saved
                                    core = null;
                                }
                                onResume();
                            }
                            if (which == AlertDialog.BUTTON_NEUTRAL) {
//                                    Intent intent = new Intent(getApplicationContext(),ChoosePDFActivity.class);
                                Intent intent = new Intent(getApplicationContext(),PenAndPDFFileChooser.class);
                                if (core.getPath() != null) intent.setData(Uri.parse(core.getPath()));
                                else if (core.getFileName() != null) intent.setData(Uri.parse(core.getFileName()));
                                intent.setAction(Intent.ACTION_PICK);
                                mNotSaveOnDestroyThisTime = mNotSaveOnPauseThisTime = true; //Do not save when we are paused for the new request
                                startActivityForResult(intent, SAVEAS_REQUEST);
                            }
                            if (which == AlertDialog.BUTTON_NEGATIVE) {
                            }
                        }
                    };
                AlertDialog alert = mAlertBuilder.create();
                alert.setTitle(getString(R.string.app_name));
//                    alert.setMessage(getString(R.string.document_has_changes_save_them_));
                if (core != null && core.getFileName() != null) alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.save), listener);
                alert.setButton(AlertDialog.BUTTON_NEUTRAL, getString(R.string.saveas), listener);
                alert.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.cancel), listener);
                alert.show();
                return true;
            case R.id.menu_gotopage:
                showGoToPageDialoge();
                return true;
            case R.id.menu_selection:
                mDocView.setMode(MuPDFReaderView.Mode.Selecting);
                mActionBarMode = ActionBarMode.Selection;
                invalidateOptionsMenu();
                return true;
            case R.id.menu_linkback:
//                setViewport(mPageBeforeInternalLinkHit,mNormalizedScaleBeforeInternalLinkHit, mNormalizedXScrollBeforeInternalLinkHit, mNormalizedYScrollBeforeInternalLinkHit);
                setViewport(mPageBeforeInternalLinkHit,mNormalizedScaleBeforeInternalLinkHit, mNormalizedXScrollBeforeInternalLinkHit, mNormalizedYScrollBeforeInternalLinkHit);
                mPageBeforeInternalLinkHit = -1;
                invalidateOptionsMenu();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }


    public void setupCore() {
        Intent intent = getIntent();
        if (Intent.ACTION_VIEW.equals(intent.getAction()))
        {
            Uri uri = intent.getData();
            String error = null;
            
            if(new File(Uri.decode(uri.getEncodedPath())).isFile()) //Uri points to a file
            {
                core = openFile(Uri.decode(uri.getEncodedPath()));
            }
            else if (uri.toString().startsWith("content://")) //Uri points to a content provider
            {
                byte buffer[] = null;
                Cursor cursor = getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.TITLE}, null, null, null); //This should be done asynchonously!
                if (cursor != null && cursor.moveToFirst())
                {
                    String displayName = null;
                    String data = null;
                    int displayNameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                    int dataIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
                    int titleIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
                    if(displayNameIndex >= 0) displayName = cursor.getString(displayNameIndex);
                    if(displayName == null && displayNameIndex >= 0) displayName = Uri.parse(cursor.getString(titleIndex)).getLastPathSegment();
                    
                    // Bundle extras = intent.getExtras();
                    // if (extras != null)
                    //     for (String key : extras.keySet())
                    //         showInfo(key+" = "+extras.get(key));
                    
                    if(dataIndex >= 0) data = cursor.getString(dataIndex);//Can return null!
                    try {
                        InputStream is = getContentResolver().openInputStream(uri);
                        if(is != null)
                        {
                            int len = is.available();
                            buffer = new byte[len];
                            is.read(buffer, 0, len);
                            is.close();
                        }
                    }
                    catch (Exception e) {
                        error = e.toString();
                    }
                    cursor.close();
                    if(buffer != null) core = openBuffer(buffer,displayName);
                }
            }
            else
            {
                error = getResources().getString(R.string.unable_to_interpret_uri)+" "+uri;
            }
            if (error != null) //There was an error
            {
                AlertDialog alert = mAlertBuilder.create();
                alert.setTitle(R.string.cannot_open_document);
                alert.setMessage(getResources().getString(R.string.reason)+": "+error);
                alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dismiss),
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {
                                        finish();
                                    }
                                });
                alert.show();
                finish();
            }
        }
        if (core != null && core.needsPassword()) {
            requestPassword();
        }
        if (core != null && core.countPages() == 0)
        {
            core = null;
        }
    }
    
    
    public void setupUI() {
        if (core == null) return;
            
        mDocView = new MuPDFReaderView(this) {
                @Override
                protected void onMoveToChild(int pageNumber) {
                    setTitle();
                    if (SearchTaskResult.get() != null && SearchTaskResult.get().pageNumber != pageNumber) {
                        SearchTaskResult.set(null);
                        resetupChildren();
                    }
                }

                @Override
                protected void onTapMainDocArea() {
                    if (mActionBarMode == ActionBarMode.Edit) 
                    {
                        mActionBarMode = ActionBarMode.Main;
                        invalidateOptionsMenu();
                    }
                }

                @Override
                protected void onDocMotion() {

                }

                @Override
                protected void onHit(Hit item) {
                    switch(item){
                        case Annotation:
                            mActionBarMode = ActionBarMode.Edit;
                            invalidateOptionsMenu();
                            break;
                        case Nothing:
                            mActionBarMode = ActionBarMode.Main;
                            invalidateOptionsMenu();
                            break;
                        case LinkInternal:
                            if(mDocView.linksEnabled()) {
                                mPageBeforeInternalLinkHit = getDisplayedViewIndex();
                                mNormalizedScaleBeforeInternalLinkHit = getNormalizedScale();
                                mNormalizedXScrollBeforeInternalLinkHit = getNormalizedXScroll();
                                mNormalizedYScrollBeforeInternalLinkHit = getNormalizedYScroll();
                            }
                            mActionBarMode = ActionBarMode.Main;
                            invalidateOptionsMenu();
                            break;
                     }
                }

                // @Override
                // protected void onSelectionStatusChanged() {
                //     invalidateOptionsMenu();
                // }

                @Override
                protected void onNumberOfStrokesChanged(int numberOfStrokes) {
                    if (numberOfStrokes>0 && mCanUndo == false) 
                    {
                        mCanUndo = true;
                        invalidateOptionsMenu();
                    }
                    else if(numberOfStrokes == 0 && mCanUndo == true)
                    {
                        mCanUndo = false;
                        invalidateOptionsMenu();
                    }
                }
                
            };
        
        mDocView.setAdapter(new MuPDFPageAdapter(this, this, core));
        
            //Enable link highlighting by default
        mDocView.setLinksEnabled(true);
        
        mSearchTask = new SearchTask(this, core) {
                @Override
                protected void onTextFound(SearchTaskResult result) { //Is called when the search task has found a page with mathing text and result.searchBoxes contains all the mathces
                    if(result.direction == -1)
                        mCurrentSearchResultOnPage = result.searchBoxes.length -1;
                    else
                        mCurrentSearchResultOnPage = 0;
                    
                    SearchTaskResult.set(result);
                        // Ask the ReaderView to move to the resulting page
                    mDocView.setDisplayedViewIndex(result.pageNumber);
                        // ... and the region on the page
                    RectF resultRect = result.searchBoxes[mCurrentSearchResultOnPage];
                    mDocView.doNextScrollWithCenter();
                    mDocView.setDocRelXScroll(resultRect.left);
                    mDocView.setDocRelYScroll(core.getPageSize(mDocView.getDisplayedViewIndex()).y-resultRect.top);
                        // Make the ReaderView act on the change to SearchTaskResult
                        // via overridden onChildSetup method.
                    mDocView.resetupChildren();
                }
            };
        
            // Reenstate last state if it was recorded
        String path = core.getPath(); //Can be null
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.SHARED_PREFERENCES_STRING, Context.MODE_MULTI_PROCESS);
        if(path != null)
            setViewport(prefs, path);
        else
            setViewport(prefs, core.getFileName());
            
        
        
//        if(core.getFileName() == null) setTitle(); //Otherwise this is already done by the DocView
        setTitle();

            // Stick the document view into a parent view
        RelativeLayout layout = new RelativeLayout(this);
        layout.addView(mDocView);
        setContentView(layout);
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        switch (requestCode) {
            case OUTLINE_REQUEST:
                if (resultCode >= 0)
                    mDocView.setDisplayedViewIndex(resultCode);
                break;
            case PRINT_REQUEST:
                // if (resultCode == RESULT_CANCELED)
                //     showInfo(getString(R.string.print_failed));
                break;
            case SAVEAS_REQUEST:
                if (resultCode == RESULT_OK) {
                    final Uri uri = intent.getData();
                    if(new File(Uri.decode(uri.getEncodedPath())).isFile()) //Warn if file already exists
                    {
                        DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    if (which == AlertDialog.BUTTON_POSITIVE) {
                                        boolean success = saveAs(uri);
                                        if(!success)
                                            showInfo(getString(R.string.error_saveing));
                                        else
                                            invalidateOptionsMenu();
                                    }
                                    if (which == AlertDialog.BUTTON_NEGATIVE) {
                                    }
                                }
                            };
                        AlertDialog alert = mAlertBuilder.create();
                        alert.setTitle("MuPDF");
                        alert.setMessage(getString(R.string.overwrite)+" "+uri.toString()+"?");
                        alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.yes), listener);
                        alert.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.no), listener);
                        alert.show();
                    }
                    else
                    {
                        boolean success = saveAs(uri);
                        if(!success)
                            showInfo(getString(R.string.error_saveing));
                        else
                            invalidateOptionsMenu();
                    }
                }
                // else if (resultCode == RESULT_CANCELED)
                // {
                //     showInfo("Aborted");
                // }
        }
        super.onActivityResult(requestCode, resultCode, intent);
    }


    private boolean saveAs(Uri uri)
        {
            if (core == null) return false;
            
                //Do not overwrite the current fiele during onPause()
            mNotSaveOnDestroyThisTime = mNotSaveOnPauseThisTime = true; 
            onPause();
                //Save the viewport under the new name
            SharedPreferences prefs = getSharedPreferences(SettingsActivity.SHARED_PREFERENCES_STRING, Context.MODE_MULTI_PROCESS);
            SharedPreferences.Editor edit = prefs.edit();
                //Save the current viewport
            saveViewport(edit, uri.getPath());
                //Save the file to the new location
            boolean success = core.saveAs(uri.toString());
            core.onDestroy();
            core = null;
                //Set the uri of this intent so that we load the new file during onResum()...
            getIntent().setData(uri);
                //... and resume
            onResume();
            return success;
        }

    private void saveViewport(SharedPreferences.Editor edit, String path) {
        if(path == null) path = "/nopath";
        edit.putInt("page"+path, mDocView.getDisplayedViewIndex());
        edit.putFloat("normalizedscale"+path, mDocView.getNormalizedScale());
        edit.putFloat("normalizedxscroll"+path, mDocView.getNormalizedXScroll());
        edit.putFloat("normalizedyscroll"+path, mDocView.getNormalizedYScroll());
        edit.commit();
    }


    private void setViewport(SharedPreferences prefs, String path) {
        if(path == null) path = "/nopath";
        setViewport(prefs.getInt("page"+path, 0),prefs.getFloat("normalizedscale"+path, 0.0f),prefs.getFloat("normalizedxscroll"+path, 0.0f), prefs.getFloat("normalizedyscroll"+path, 0.0f));
        // mDocView.setDisplayedViewIndex(prefs.getInt("page"+path, 0));
        // mDocView.setScale(prefs.getFloat("normalizedscale"+path, 0.0f)); //If normalizedScale=0.0 nothing happens
        // mDocView.setScroll(prefs.getFloat("normalizedxscroll"+path, 0.0f), prefs.getFloat("normalizedyscroll"+path, 0.0f));
    }

    private void setViewport(int page, float normalizedscale, float normalizedxscroll, float normalizedyscroll) {
        mDocView.setDisplayedViewIndex(page);
        mDocView.setNormalizedScale(normalizedscale);
        mDocView.setNormalizedScroll(normalizedxscroll, normalizedyscroll);
    }
    
    public Object onRetainNonConfigurationInstance()
	{
            MuPDFCore mycore = core;
            core = null;
            return mycore;
	}

    
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        
        outState.putString("ActionBarMode", mActionBarMode.toString());
        outState.putString("AcceptMode", mAcceptMode.toString());
        outState.putInt("PageBeforeInternalLinkHit", mPageBeforeInternalLinkHit);
        outState.putFloat("NormalizedScaleBeforeInternalLinkHit", mNormalizedScaleBeforeInternalLinkHit);
        outState.putFloat("NormalizedXScrollBeforeInternalLinkHit", mNormalizedXScrollBeforeInternalLinkHit);
        outState.putFloat("NormalizedYScrollBeforeInternalLinkHit", mNormalizedYScrollBeforeInternalLinkHit);   
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPref, String key) {
        
        if (sharedPref.getBoolean(SettingsActivity.PREF_KEEP_SCREEN_ON, false ))
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        if(core != null) core.onSharedPreferenceChanged(sharedPref, key);
            //mDocView.onSharedPreferenceChanged(sharedPref, key);//This should be used to set preferences in page views...
    }    

    
    private void printDoc() {
        // if (core.getFileName() == null)
        // {
        //     showInfo(getString(R.string.save_before_print));
        //     return;
        // }
        if (!core.fileFormat().startsWith("PDF")) {
            showInfo(getString(R.string.format_currently_not_supported));
            return;
        }

        Intent intent = getIntent();
        Uri docUri = intent != null ? intent.getData() : null;

        if (docUri == null) {
            showInfo(getString(R.string.print_failed));
        }

        if (docUri.getScheme() == null)
            docUri = Uri.parse("file://"+docUri.toString());

        Intent printIntent = new Intent(this, PrintDialogActivity.class);
        printIntent.setDataAndType(docUri, "aplication/pdf");
        printIntent.putExtra("title", core.getFileName());
        startActivityForResult(printIntent, PRINT_REQUEST);
    }

    
    private void showInfo(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
    }    

    
    public void requestPassword() {
        mPasswordView = new EditText(this);
        mPasswordView.setInputType(EditorInfo.TYPE_TEXT_VARIATION_PASSWORD);
        mPasswordView.setTransformationMethod(new PasswordTransformationMethod());

        AlertDialog alert = mAlertBuilder.create();
        alert.setTitle(R.string.enter_password);
        alert.setView(mPasswordView);
        alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.okay),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                    // if (core.authenticatePassword(mPasswordView.getText().toString())) {
                                    //     setupUI();
				if (!core.authenticatePassword(mPasswordView.getText().toString()))
                                    requestPassword();
                                
                            }
                        });
        alert.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.cancel),
                        new DialogInterface.OnClickListener() {
                            
                            public void onClick(DialogInterface dialog, int which) {
				finish();
                            }
                        });
        alert.show();
    }


    private void showGoToPageDialoge() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine();
        new AlertDialog.Builder(this)
            .setTitle(R.string.dialog_gotopage_title)
            .setPositiveButton(R.string.dialog_gotopage_ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                            // User clicked OK button
                        int pageNumber = Integer.parseInt(input.getText().toString());
                        mDocView.setDisplayedViewIndex(pageNumber == 0 ? 0 : pageNumber -1 );
                    }
                })
            .setNegativeButton(R.string.dialog_gotopage_cancel, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                            // User cancelled the dialog
                    }
                })
            .setView(input)
            .show();
    }

    
    private void search(int direction) {
        int displayPage = mDocView.getDisplayedViewIndex();
        SearchTaskResult r = SearchTaskResult.get();

        if( r != null && r.pageNumber == displayPage && r.searchBoxes.length > mCurrentSearchResultOnPage+direction && mCurrentSearchResultOnPage+direction >= 0 ) //There still are results on the current page
        {
            mCurrentSearchResultOnPage += direction;
            RectF resultRect = r.searchBoxes[mCurrentSearchResultOnPage];
            mDocView.doNextScrollWithCenter();
            mDocView.setDocRelXScroll(resultRect.centerX()); 
            mDocView.setDocRelYScroll(core.getPageSize(displayPage).y-resultRect.centerY());
//            showInfo("number "+mCurrentSearchResultOnPage+" on page "+r.pageNumber+" at "+resultRect.left+" "+resultRect.top+" (page height="+core.getPageSize(displayPage).y+")");
        }
        else if(displayPage+direction < core.countPages() && displayPage+direction >= 0) //Try to find more on a differnt page
        {
            int searchPage = r != null ? r.pageNumber : -1;
            mSearchTask.go(mQuery, direction, displayPage, searchPage);
        }
    }

	// @Override
	// public boolean onSearchRequested() {
	// 	if (mButtonsVisible && mTopBarMode == TopBarMode.Search) {
	// 		hideButtons();
	// 	} else {
	// 		showButtons();
	// 		searchModeOn();
	// 	}
	// 	return super.onSearchRequested();
	// }

	// @Override
	// public boolean onPrepareOptionsMenu(Menu menu) {
	// 	if (mButtonsVisible && mTopBarMode != TopBarMode.Search) {
	// 		hideButtons();
	// 	} else {
	// 		showButtons();
	// 		searchModeOff();
	// 	}
	// 	return super.onPrepareOptionsMenu(menu);
	// }


    @Override
    public void onBackPressed() {
        if (mActionBarMode == ActionBarMode.Annot) return;
        if (mActionBarMode == ActionBarMode.Search) {
            hideKeyboard();
            SearchTaskResult.set(null);
            mDocView.resetupChildren();
            mActionBarMode = ActionBarMode.Main;
            invalidateOptionsMenu();
            return;
        }    
        
        if (core.hasChanges()) {
            DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == AlertDialog.BUTTON_POSITIVE) {
                            boolean success = core.save();
                            if(!success) showInfo(getString(R.string.error_saveing));
                            mNotSaveOnDestroyThisTime = mNotSaveOnPauseThisTime = true; //No need to save twice
                            finish();
                        }
                        if (which == AlertDialog.BUTTON_NEGATIVE) {
                            mNotSaveOnDestroyThisTime = mNotSaveOnPauseThisTime = true;
                            finish();
                        }
                        if (which == AlertDialog.BUTTON_NEUTRAL) {
                        }
                    }
                };
            AlertDialog alert = mAlertBuilder.create();
            alert.setTitle("MuPDF");
            alert.setMessage(getString(R.string.document_has_changes_save_them_));
            alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.yes), listener);
            alert.setButton(AlertDialog.BUTTON_NEUTRAL, getString(R.string.cancel), listener);
            alert.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.no), listener);
            alert.show();
        } else {
            super.onBackPressed();
        }
    }

    
    @Override
    public void performPickFor(FilePicker picker) {
        mFilePicker = picker;
        Intent intent = new Intent(this, ChoosePDFActivity.class);
        startActivityForResult(intent, FILEPICK_REQUEST);
    }

    private void setTitle() {
        if (core == null || mDocView == null)  return;
        int pageNumber = mDocView.getDisplayedViewIndex();
        String title = Integer.toString(pageNumber+1)+"/"+Integer.toString(core.countPages());
        if(core.getFileName() != null) title+=" "+core.getFileName();
        getActionBar().setTitle(title);
    }
    
    
    private void hideKeyboard()
    {
        InputMethodManager inputMethodManager = (InputMethodManager)  getSystemService(INPUT_METHOD_SERVICE);
        inputMethodManager.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
    }
}
