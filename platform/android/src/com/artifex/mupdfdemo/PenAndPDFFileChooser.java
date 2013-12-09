package com.artifex.mupdfdemo;

import java.io.File;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.support.v4.view.ViewPager;
import android.os.Bundle;
import android.app.ActionBar;
import android.app.FragmentTransaction;
import android.app.FragmentManager;
import android.view.LayoutInflater;
import android.widget.Toast;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuInflater;
import android.content.Intent;
import java.lang.reflect.InvocationTargetException;
import android.preference.PreferenceManager;
import java.lang.reflect.Method;
import java.lang.reflect.Field;

public class PenAndPDFFileChooser extends Activity implements RecentFilesFragment.goToDirInterface {

    private SwipeFragmentPagerAdapter mFragmentPagerAdapter;
    private ViewPager mViewPager;

    private String mFilename = null;
    
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
                
                //Set default preferences on first start
        PreferenceManager.setDefaultValues(this, SettingsActivity.SHARED_PREFERENCES_STRING, MODE_MULTI_PROCESS, R.xml.preferences, false);

            //Setup the UI
        setContentView(R.layout.chooser);
            //Create the fragment adapter
        mFragmentPagerAdapter = new SwipeFragmentPagerAdapter(getFragmentManager(), getLayoutInflater());
            //Add the fragments
        FileBrowserFragment fileBrowserFragment = FileBrowserFragment.newInstance(getIntent());
        RecentFilesFragment recentFilesFragment = RecentFilesFragment.newInstance(getIntent());
        mFragmentPagerAdapter.add(fileBrowserFragment);
        mFragmentPagerAdapter.add(recentFilesFragment);
            //Add the fragment adapter to he view
        mViewPager = (ViewPager) findViewById(R.id.pager);
        mViewPager.setAdapter(mFragmentPagerAdapter);

            // Specify that tabs should be displayed in the action bar.
        final ActionBar actionBar = getActionBar();
        actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);

            // Create a tab listener that is called when the user clicks tabs.
        ActionBar.TabListener tabListener = new ActionBar.TabListener() {
                public void onTabSelected(ActionBar.Tab tab, FragmentTransaction ft) {
                    mViewPager.setCurrentItem(tab.getPosition());
                }
                
                public void onTabUnselected(ActionBar.Tab tab, FragmentTransaction ft) {}
                
                public void onTabReselected(ActionBar.Tab tab, FragmentTransaction ft) {}
            };
        
            // Add 2 tabs, specifying the tab's text and TabListener
        for (int i = 0; i < mFragmentPagerAdapter.getCount(); i++) {
            String tabLable;
            switch(i)
            {
                case 0:
                    tabLable = getString(R.string.browse);
                    break;
                case 1:
                    tabLable = getString(R.string.recent);
                    break;
                default:
                    tabLable = "unknown";
            }
            actionBar.addTab(
                actionBar.newTab()
                .setText(tabLable)
                .setTabListener(tabListener));
        }

        mViewPager.setOnPageChangeListener(
            new ViewPager.SimpleOnPageChangeListener() {
                @Override
                public void onPageSelected(int position) {
                        //When swiping between pages, select the corresponding tab.
                    getActionBar().setSelectedNavigationItem(position);
                }
            });
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {//Inflates the options menu
        
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.choosepdf_menu, menu);
        return true;
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {//Handel clicks in the options menu
        switch (item.getItemId()) 
        {
            case R.id.menu_settings:
                Intent intent = new Intent(this,SettingsActivity.class);
                startActivity(intent);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void goToDir(File dir) {
//        ((FileBrowserFragment)mFragmentPagerAdapter.getItem(0)).goToDir(dir);//Doesn't do what you would think it does
//        findFragmentByTag("android:switcher:" + R.id.pager + ":" + 0).goToDir(dir);//This is how one is supposed to do it
        ((FileBrowserFragment)getFragmentByPosition(mViewPager,mFragmentPagerAdapter, 0)).goToDir(dir);
        mViewPager.setCurrentItem(0);        
    }
    
        //Black magic :-)
    private Fragment getFragmentByPosition(ViewPager viewPager, FragmentPagerAdapter fragmentPagerAdapter, int position) {
        Fragment fragment = getFragmentManager().findFragmentByTag("android:switcher:" + viewPager.getId() + ":"+ position);
        if(fragment==null) fragment = fragmentPagerAdapter.getItem(position);
        return fragment;
    }
}
