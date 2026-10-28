/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.android.notepad;

import com.example.android.notepad.NotePad;

import android.app.ListActivity;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.SearchView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

/**
 * Displays a list of notes. Will display notes from the {@link Uri}
 * provided in the incoming Intent if there is one, otherwise it defaults to displaying the
 * contents of the {@link NotePadProvider}.
 */
public class NotesList extends ListActivity {

    private static final String TAG = "NotesList";

    private static final String[] PROJECTION = new String[] {
            NotePad.Notes._ID,
            NotePad.Notes.COLUMN_NAME_TITLE,
            NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE,
            NotePad.Notes.COLUMN_NAME_CATEGORY,
            NotePad.Notes.COLUMN_NAME_NOTE
    };

    private static final int COLUMN_INDEX_TITLE = 1;

    private SearchView mSearchView;
    private Spinner mSpFilterCategory;
    private List<String> mFilterCatList;
    private String mSelectedFilterCat;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.notes_list);

        setDefaultKeyMode(DEFAULT_KEYS_SHORTCUT);

        Intent intent = getIntent();
        if (intent.getData() == null) {
            intent.setData(NotePad.Notes.CONTENT_URI);
        }

        getListView().setOnCreateContextMenuListener(this);

        mSpFilterCategory = (Spinner) findViewById(R.id.sp_filter_category);
        loadFilterCategory();

        Cursor cursor = managedQuery(
                getIntent().getData(),
                PROJECTION,
                null,
                null,
                NotePad.Notes.DEFAULT_SORT_ORDER
        );

        String[] dataColumns = {
                NotePad.Notes.COLUMN_NAME_TITLE,
                NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE
        } ;

        int[] viewIDs = {
                android.R.id.text1,
                R.id.note_time
        };

        SimpleCursorAdapter adapter
                = new SimpleCursorAdapter(
                this,
                R.layout.noteslist_item,
                cursor,
                dataColumns,
                viewIDs,
                0
        );

        adapter.setViewBinder(new SimpleCursorAdapter.ViewBinder() {
            @Override
            public boolean setViewValue(View view, Cursor cursor, int columnIndex) {
                String colName = cursor.getColumnName(columnIndex);
                if(colName.equals(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE)){
                    long timeMs = cursor.getLong(columnIndex);
                    TextView tv = (TextView) view;
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy‑MM‑dd HH:mm", Locale.CHINA);
                    tv.setText(sdf.format(new Date(timeMs)));
                    return true;
                }
                return false;
            }
        });

        setListAdapter(adapter);

        mSearchView = (SearchView) findViewById(R.id.search_view);
        mSearchView.setIconifiedByDefault(false);
        mSearchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                doSearch(query);
                return true;
            }
            @Override
            public boolean onQueryTextChange(String newText) {
                doSearch(newText);
                return true;
            }
        });
    }

    private void loadFilterCategory(){
        mFilterCatList = new ArrayList<>();
        mFilterCatList.add("全部笔记");

        Cursor c = getContentResolver().query(
                NotePad.Notes.CONTENT_URI,
                new String[]{NotePad.Notes.COLUMN_NAME_CATEGORY},
                null,null,null
        );
        if(c != null){
            while(c.moveToNext()){
                String cat = c.getString(0);
                if(cat != null && !mFilterCatList.contains(cat)){
                    mFilterCatList.add(cat);
                }
            }
            c.close();
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                mFilterCatList
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSpFilterCategory.setAdapter(adapter);

        mSpFilterCategory.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String item = mFilterCatList.get(position);
                if("全部笔记".equals(item)){
                    mSelectedFilterCat = null;
                }else{
                    mSelectedFilterCat = item;
                }
                String keyword = mSearchView.getQuery().toString();
                doSearch(keyword);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void doSearch(String keyword){
        List<String> whereArgsList = new ArrayList<>();
        StringBuilder selectionSb = new StringBuilder();

        boolean hasKeyword = keyword != null && !keyword.trim().isEmpty();
        if(hasKeyword){
            selectionSb.append(" (")
                    .append(NotePad.Notes.COLUMN_NAME_TITLE).append(" LIKE ? OR ")
                    .append(NotePad.Notes.COLUMN_NAME_NOTE).append(" LIKE ? ) ");
            String likeStr = "%"+keyword.trim()+"%";
            whereArgsList.add(likeStr);
            whereArgsList.add(likeStr);
        }

        if(mSelectedFilterCat != null){
            if(hasKeyword){
                selectionSb.append(" AND ");
            }
            selectionSb.append(NotePad.Notes.COLUMN_NAME_CATEGORY).append(" = ? ");
            whereArgsList.add(mSelectedFilterCat);
        }

        String selection = selectionSb.length()>0 ? selectionSb.toString() : null;
        String[] selectionArgs = whereArgsList.size()>0 ? whereArgsList.toArray(new String[0]) : null;

        Cursor cursor = getContentResolver().query(
                getIntent().getData(),
                PROJECTION,
                selection,
                selectionArgs,
                NotePad.Notes.DEFAULT_SORT_ORDER
        );
        SimpleCursorAdapter adapter = (SimpleCursorAdapter) getListAdapter();
        adapter.swapCursor(cursor);
    }

    /** 修复闪退：批量导出 */
    /**批量导出全部笔记（修复：缺少note字段 + UTF8编码）*/
    private void batchExportAllNotes(){
        SimpleCursorAdapter adapter = (SimpleCursorAdapter) getListAdapter();
        Cursor cursor = adapter.getCursor();
        if(cursor == null || cursor.getCount() ==0){
            Toast.makeText(this,"没有笔记可导出",Toast.LENGTH_SHORT).show();
            return;
        }

        File baseDir = getExternalFilesDir(null);
        if(baseDir == null){
            Toast.makeText(this,"无法获取存储目录！",Toast.LENGTH_SHORT).show();
            return;
        }
        File outDir = new File(baseDir,"batch_notes");
        if(!outDir.exists()){
            boolean createOk = outDir.mkdirs();
            if(!createOk){
                Toast.makeText(this,"创建导出目录失败！",Toast.LENGTH_SHORT).show();
                return;
            }
        }

        int successCount = 0;
        int failCount = 0;
        cursor.moveToPosition(-1);
        while(cursor.moveToNext()){
            try {
                String title = cursor.getString(cursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE));
                String content = cursor.getString(cursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE));

                if(title == null || title.trim().isEmpty()){
                    title = "无标题笔记_" + System.currentTimeMillis();
                }
                if(content == null){
                    content = "";
                }
                // 过滤文件名非法字符
                String fileName = title.replaceAll("[\\\\/:*?\"<>|]","_") + ".txt";
                File f = new File(outDir,fileName);
                // 指定UTF-8，避免中文乱码
                try(FileOutputStream fos = new FileOutputStream(f)){
                    fos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    successCount++;
                }
            }catch (Exception e){
                e.printStackTrace();
                failCount++;
            }
        }
        Toast.makeText(this,
                "批量导出完成！成功："+successCount+" 失败："+failCount+"\n路径："+outDir.getAbsolutePath(),
                Toast.LENGTH_LONG).show();
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.list_options_menu, menu);

        Intent intent = new Intent(null, getIntent().getData());
        intent.addCategory(Intent.CATEGORY_ALTERNATIVE);
        menu.addIntentOptions(Menu.CATEGORY_ALTERNATIVE, 0, 0,
                new ComponentName(this, NotesList.class), null, intent, 0, null);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        ClipboardManager clipboard = (ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);

        MenuItem mPasteItem = menu.findItem(R.id.menu_paste);
        if (clipboard.hasPrimaryClip()) {
            mPasteItem.setEnabled(true);
        } else {
            mPasteItem.setEnabled(false);
        }

        final boolean haveItems = getListAdapter().getCount() > 0;
        if (haveItems) {
            Uri uri = ContentUris.withAppendedId(getIntent().getData(), getSelectedItemId());
            Intent[] specifics = new Intent[1];
            specifics[0] = new Intent(Intent.ACTION_EDIT, uri);
            MenuItem[] items = new MenuItem[1];
            Intent intent = new Intent(null, uri);
            intent.addCategory(Intent.CATEGORY_ALTERNATIVE);

            menu.addIntentOptions(
                    Menu.CATEGORY_ALTERNATIVE,
                    Menu.NONE,
                    Menu.NONE,
                    null,
                    specifics,
                    intent,
                    Menu.NONE,
                    items
            );
            if (items[0] != null) {
                items[0].setShortcut('1', 'e');
            }
        } else {
            menu.removeGroup(Menu.CATEGORY_ALTERNATIVE);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_add) {
            startActivity(new Intent(Intent.ACTION_INSERT, getIntent().getData()));
            return true;
        } else if (item.getItemId() == R.id.menu_paste) {
            startActivity(new Intent(Intent.ACTION_PASTE, getIntent().getData()));
            return true;
        } else if(item.getItemId() == R.id.menu_batch_export){
            batchExportAllNotes();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenuInfo menuInfo) {
        AdapterView.AdapterContextMenuInfo info;
        try {
            info = (AdapterView.AdapterContextMenuInfo) menuInfo;
        } catch (ClassCastException e) {
            Log.e(TAG, "bad menuInfo", e);
            return;
        }

        Cursor cursor = (Cursor) getListAdapter().getItem(info.position);
        if (cursor == null) {
            return;
        }

        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.list_context_menu, menu);
        menu.setHeaderTitle(cursor.getString(COLUMN_INDEX_TITLE));

        Intent intent = new Intent(null, Uri.withAppendedPath(getIntent().getData(),
                Integer.toString((int) info.id) ));
        intent.addCategory(Intent.CATEGORY_ALTERNATIVE);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        menu.addIntentOptions(Menu.CATEGORY_ALTERNATIVE, 0, 0,
                new ComponentName(this, NotesList.class), null, intent, 0, null);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info;
        try {
            info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        } catch (ClassCastException e) {
            Log.e(TAG, "bad menuInfo", e);
            return false;
        }
        Uri noteUri = ContentUris.withAppendedId(getIntent().getData(), info.id);

        int id = item.getItemId();
        if (id == R.id.context_open) {
            startActivity(new Intent(Intent.ACTION_EDIT, noteUri));
            return true;
        } else if (id == R.id.context_copy) {
            ClipboardManager clipboard = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newUri(
                    getContentResolver(),
                    "Note",
                    noteUri));
            return true;
        } else if (id == R.id.context_delete) {
            getContentResolver().delete(
                    noteUri,
                    null,
                    null
            );
            return true;
        }
        return super.onContextItemSelected(item);
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        Uri uri = ContentUris.withAppendedId(getIntent().getData(), id);
        String action = getIntent().getAction();
        if (Intent.ACTION_PICK.equals(action) || Intent.ACTION_GET_CONTENT.equals(action)) {
            setResult(RESULT_OK, new Intent().setData(uri));
        } else {
            startActivity(new Intent(Intent.ACTION_EDIT, uri));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadFilterCategory();
    }
}
