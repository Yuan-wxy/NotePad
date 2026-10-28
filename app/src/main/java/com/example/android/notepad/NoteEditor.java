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

import static com.example.android.notepad.R.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * This Activity handles "editing" a note, where editing is responding to
 * {@link Intent#ACTION_VIEW} (request to view data), edit a note
 * {@link Intent#ACTION_EDIT}, create a note {@link Intent#ACTION_INSERT}, or
 * create a new note from the current contents of the clipboard {@link Intent#ACTION_PASTE}.
 */
public class NoteEditor extends Activity {
    // For logging and debugging purposes
    private static final String TAG = "NoteEditor";

    private static final String[] PROJECTION =
            new String[] {
                    NotePad.Notes._ID,
                    NotePad.Notes.COLUMN_NAME_TITLE,
                    NotePad.Notes.COLUMN_NAME_NOTE,
                    NotePad.Notes.COLUMN_NAME_CATEGORY
            };

    private static final String ORIGINAL_CONTENT = "origContent";
    private static final int STATE_EDIT = 0;
    private static final int STATE_INSERT = 1;

    private int mState;
    private Uri mUri;
    private Cursor mCursor;
    private LinedEditText mText;
    private String mOriginalContent;

    private Spinner mSpinnerCategory;
    private List<String> mCategoryList;
    private String mSelectedCategory;

    public static class LinedEditText extends EditText {
        private Rect mRect;
        private Paint mPaint;

        public LinedEditText(Context context, AttributeSet attrs) {
            super(context, attrs);
            mRect = new Rect();
            mPaint = new Paint();
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setColor(0x800000FF);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int count = getLineCount();
            Rect r = mRect;
            Paint paint = mPaint;
            for (int i = 0; i < count; i++) {
                int baseline = getLineBounds(i, r);
                canvas.drawLine(r.left, baseline + 1, r.right, baseline + 1, paint);
            }
            super.onDraw(canvas);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.note_editor);

        final Intent intent = getIntent();
        final String action = intent.getAction();

        if (Intent.ACTION_EDIT.equals(action)) {
            mState = STATE_EDIT;
            mUri = intent.getData();
        } else if (Intent.ACTION_INSERT.equals(action)
                || Intent.ACTION_PASTE.equals(action)) {
            mState = STATE_INSERT;
            mUri = getContentResolver().insert(intent.getData(), null);
            if (mUri == null) {
                Log.e(TAG, "Failed to insert new note into " + getIntent().getData());
                finish();
                return;
            }
            setResult(RESULT_OK, (new Intent()).setAction(mUri.toString()));
        } else {
            Log.e(TAG, "Unknown action, exiting");
            finish();
            return;
        }

        mCursor = getContentResolver().query(mUri, PROJECTION, null, null, null);

        if (Intent.ACTION_PASTE.equals(action)) {
            performPaste();
            mState = STATE_EDIT;
        }

        mText = (LinedEditText) findViewById(R.id.note);
        mSpinnerCategory = (Spinner) findViewById(R.id.sp_category);

        loadAllCategory();

        if (savedInstanceState != null) {
            mOriginalContent = savedInstanceState.getString(ORIGINAL_CONTENT);
        }
    }

    private void loadAllCategory(){
        mCategoryList = new ArrayList<>();
        mCategoryList.add(NotePad.Notes.CATEGORY_DEFAULT);

        Cursor c = getContentResolver().query(
                NotePad.Notes.CONTENT_URI,
                new String[]{NotePad.Notes.COLUMN_NAME_CATEGORY},
                null,
                null,
                null
        );
        if(c != null){
            while(c.moveToNext()){
                String cat = c.getString(0);
                if(cat != null && !mCategoryList.contains(cat)){
                    mCategoryList.add(cat);
                }
            }
            c.close();
        }
        mCategoryList.add("+ 新建分类");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                mCategoryList
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSpinnerCategory.setAdapter(adapter);

        mSpinnerCategory.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String item = mCategoryList.get(position);
                if("+ 新建分类".equals(item)){
                    showCreateCategoryDialog();
                }else{
                    mSelectedCategory = item;
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        if(mCursor != null && mCursor.moveToFirst()){
            String currentCat = mCursor.getString(mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_CATEGORY));
            int pos = mCategoryList.indexOf(currentCat);
            if(pos >= 0){
                mSpinnerCategory.setSelection(pos);
                mSelectedCategory = currentCat;
            }
        }
    }

    private void showCreateCategoryDialog(){
        final EditText editText = new EditText(this);
        new AlertDialog.Builder(this)
                .setTitle("新建分类")
                .setMessage("请输入分类名称")
                .setView(editText)
                .setPositiveButton("确定", (dialog, which) -> {
                    String newCat = editText.getText().toString().trim();
                    if(newCat.isEmpty()){
                        Toast.makeText(NoteEditor.this,"分类名称不能为空",Toast.LENGTH_SHORT).show();
                        mSpinnerCategory.setSelection(0);
                        mSelectedCategory = NotePad.Notes.CATEGORY_DEFAULT;
                        return;
                    }
                    if(!mCategoryList.contains(newCat)){
                        mCategoryList.remove("+ 新建分类");
                        mCategoryList.add(newCat);
                        mCategoryList.add("+ 新建分类");
                        ((ArrayAdapter<?>)mSpinnerCategory.getAdapter()).notifyDataSetChanged();
                    }
                    int newPos = mCategoryList.indexOf(newCat);
                    mSpinnerCategory.setSelection(newPos);
                    mSelectedCategory = newCat;
                })
                .setNegativeButton("取消", (dialog, which) -> {
                    mSpinnerCategory.setSelection(0);
                    mSelectedCategory = NotePad.Notes.CATEGORY_DEFAULT;
                })
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mCursor != null && mCursor.moveToFirst()) {
            if (mState == STATE_EDIT) {
                int colTitleIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE);
                String title = mCursor.getString(colTitleIndex);
                setTitle(getString(R.string.title_edit,title));
            } else if (mState == STATE_INSERT) {
                setTitle(getText(R.string.title_create));
            }

            int colNoteIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE);
            String note = mCursor.getString(colNoteIndex);
            mText.setTextKeepState(note);
            if (mOriginalContent == null) {
                mOriginalContent = note;
            }
        } else {
            setTitle(getText(R.string.error_title));
            mText.setText(getText(R.string.error_message));
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString(ORIGINAL_CONTENT, mOriginalContent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mCursor != null) {
            String text = mText.getText().toString();
            int length = text.length();

            if (isFinishing() && (length == 0)) {
                setResult(RESULT_CANCELED);
                deleteNote();
            } else if (mState == STATE_EDIT) {
                updateNote(text, null, mSelectedCategory);
            } else if (mState == STATE_INSERT) {
                updateNote(text, text, mSelectedCategory);
                mState = STATE_EDIT;
            }
            mCursor.close();
            mCursor = null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.editor_options_menu, menu);
        if (mState == STATE_EDIT) {
            Intent intent = new Intent(null, mUri);
            intent.addCategory(Intent.CATEGORY_ALTERNATIVE);
            menu.addIntentOptions(Menu.CATEGORY_ALTERNATIVE, 0, 0,
                    new ComponentName(this, NoteEditor.class), null, intent, 0, null);
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if(mCursor == null) return super.onPrepareOptionsMenu(menu);
        int colNoteIndex = mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE);
        String savedNote = mCursor.getString(colNoteIndex);
        String currentNote = mText.getText().toString();
        menu.findItem(R.id.menu_revert).setVisible(!savedNote.equals(currentNote));
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_save) {
            String text = mText.getText().toString();
            updateNote(text, null, mSelectedCategory);
            finish();
        } else if (id == R.id.menu_delete) {
            deleteNote();
            finish();
        } else if (id == R.id.menu_revert) {
            cancelNote();
        }else if(id == R.id.menu_export_single){
            exportSingleNote();
        }
        return super.onOptionsItemSelected(item);
    }

    /** 单条笔记导出，修复空标题闪退 */
    private void exportSingleNote(){
        if(mCursor == null || !mCursor.moveToFirst()){
            Toast.makeText(this,"导出失败，无笔记数据",Toast.LENGTH_SHORT).show();
            return;
        }
        File baseDir = getExternalFilesDir(null);
        if(baseDir == null){
            Toast.makeText(this,"无法获取存储目录",Toast.LENGTH_SHORT).show();
            return;
        }
        String title = mCursor.getString(mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE));
        String content = mCursor.getString(mCursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE));
        if(title == null || title.trim().isEmpty()){
            title = "无标题笔记_" + System.currentTimeMillis();
        }
        String fileName = title.replaceAll("[\\\\/:*?\"<>|]","_") + ".txt";
        File exportFile = new File(baseDir,fileName);
        try(FileOutputStream fos = new FileOutputStream(exportFile)){
            fos.write(content.getBytes());
            Toast.makeText(this,"导出成功！路径："+exportFile.getAbsolutePath(),Toast.LENGTH_LONG).show();
        }catch (IOException e){
            Toast.makeText(this,"导出失败",Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    private final void performPaste() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ContentResolver cr = getContentResolver();
        ClipData clip = clipboard.getPrimaryClip();
        if (clip != null) {
            String text = null;
            String title = null;
            ClipData.Item item = clip.getItemAt(0);
            Uri uri = item.getUri();
            if (uri != null && NotePad.Notes.CONTENT_ITEM_TYPE.equals(cr.getType(uri))) {
                Cursor orig = cr.query(uri, PROJECTION, null, null, null);
                if (orig != null) {
                    if (orig.moveToFirst()) {
                        int colNoteIndex = orig.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE);
                        int colTitleIndex = orig.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE);
                        text = orig.getString(colNoteIndex);
                        title = orig.getString(colTitleIndex);
                    }
                    orig.close();
                }
            }
            if (text == null) {
                text = item.coerceToText(this).toString();
            }
            updateNote(text, title,null);
        }
    }

    private final void updateNote(String text, String title,String category) {
        ContentValues values = new ContentValues();
        values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, System.currentTimeMillis());
        if(mSelectedCategory != null){
            values.put(NotePad.Notes.COLUMN_NAME_CATEGORY,mSelectedCategory);
        }else if(category != null){
            values.put(NotePad.Notes.COLUMN_NAME_CATEGORY,category);
        }

        if (mState == STATE_INSERT) {
            if (title == null) {
                int length = text.length();
                title = text.substring(0, Math.min(30, length));
                if (length > 30) {
                    int lastSpace = title.lastIndexOf(' ');
                    if (lastSpace > 0) title = title.substring(0, lastSpace);
                }
            }
            values.put(NotePad.Notes.COLUMN_NAME_TITLE, title);
        } else if (title != null) {
            values.put(NotePad.Notes.COLUMN_NAME_TITLE, title);
        }
        values.put(NotePad.Notes.COLUMN_NAME_NOTE, text);
        getContentResolver().update(mUri, values, null, null);
    }

    private final void cancelNote() {
        if (mCursor != null) {
            if (mState == STATE_EDIT) {
                mCursor.close();
                mCursor = null;
                ContentValues values = new ContentValues();
                values.put(NotePad.Notes.COLUMN_NAME_NOTE, mOriginalContent);
                getContentResolver().update(mUri, values, null, null);
            } else if (mState == STATE_INSERT) {
                deleteNote();
            }
        }
        setResult(RESULT_CANCELED);
        finish();
    }

    private final void deleteNote() {
        if (mCursor != null) {
            mCursor.close();
            mCursor = null;
            getContentResolver().delete(mUri, null, null);
            mText.setText("");
        }
    }
}
