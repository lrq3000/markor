package net.gsantner.markor.frontend.textview;

import android.content.Context;
import android.graphics.Canvas;
import android.view.ActionMode;
import android.view.GestureDetector;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.graphics.Paint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.text.Layout;
import android.text.Editable;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Handles custom multiline and single-line selection for RecyclerTextEditor.
 * Bypasses native EditText selection to allow selection spanning multiple RecyclerView items.
 */
public class RecyclerSelectionManager extends RecyclerView.ItemDecoration implements RecyclerView.OnItemTouchListener {

    private final RecyclerTextEditor editor;
    private final Context context;

    private int selectionStart = -1;
    private int selectionEnd = -1;

    // Flag to denote if a custom selection is currently active
    private boolean isSelectionActive = false;

    // Action mode for copy/paste/cut
    private ActionMode actionMode;

    // Gesture detector to detect long press
    private final GestureDetector gestureDetector;

    // Selection handles paint
    private final Paint handlePaint;
    private final float handleRadius;
    private final float handleWidth;

    // Drag handle state
    private boolean draggingStartHandle = false;
    private boolean draggingEndHandle = false;
    private final float TOUCH_SLOP_RADIUS;

    // Auto-scrolling state
    private boolean isAutoScrolling = false;
    private int autoScrollDy = 0;
    private final Runnable autoScrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (isAutoScrolling && editor != null) {
                editor.scrollBy(0, autoScrollDy);
                // Also update selection while scrolling if we have last touch coordinates
                if (lastTouchX != -1 && lastTouchY != -1) {
                    updateSelectionFromTouch(lastTouchX, lastTouchY);
                }
                editor.postOnAnimation(this);
            }
        }
    };
    private float lastTouchX = -1;
    private float lastTouchY = -1;

    private final int SCROLL_THRESHOLD = 100; // pixels from edge to trigger scroll
    private final int MAX_SCROLL_SPEED = 30; // pixels per frame

    public RecyclerSelectionManager(@NonNull RecyclerTextEditor editor) {
        this.editor = editor;
        this.context = editor.getContext();

        // Setup paint for handles
        handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        handlePaint.setColor(TextViewUtils.getHighlightColor(context)); // Ideally a slightly darker/solid version of highlight color, but this works
        // Try to get primary color for handles
        android.util.TypedValue typedValue = new android.util.TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.colorPrimary, typedValue, true)) {
            handlePaint.setColor(typedValue.data);
        }

        handleRadius = context.getResources().getDisplayMetrics().density * 8;
        handleWidth = context.getResources().getDisplayMetrics().density * 2;
        TOUCH_SLOP_RADIUS = handleRadius * 3; // larger touch target for handles

        // Listen for long presses and double taps to start custom selection
        this.gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                int offset = getGlobalOffsetAtPosition(e.getX(), e.getY());
                if (offset != -1) {
                    startSelection(offset, offset);
                }
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                int offset = getGlobalOffsetAtPosition(e.getX(), e.getY());
                if (offset != -1) {
                    // Select a simple word boundary around the offset
                    int[] wordBounds = findWordBounds(offset);
                    startSelection(wordBounds[0], wordBounds[1]);
                    return true;
                }
                return false;
            }
        });
    }

    public int getSelectionStart() {
        return Math.min(selectionStart, selectionEnd);
    }

    public int getSelectionEnd() {
        return Math.max(selectionStart, selectionEnd);
    }

    public boolean hasSelection() {
        return isSelectionActive && selectionStart != -1 && selectionEnd != -1 && selectionStart != selectionEnd;
    }

    private void startSelection(int start, int end) {
        isSelectionActive = true;
        selectionStart = start;
        selectionEnd = end;

        editor.setSelection(start); // Clear native selection cursor
        startActionMode();
        editor.refreshVisibleLineEditors(true);
        editor.invalidate();
    }

    private int[] findWordBounds(int offset) {
        if (editor == null || editor.getText() == null) return new int[]{offset, offset};
        CharSequence text = editor.getText();
        if (offset < 0 || offset >= text.length()) return new int[]{offset, offset};

        int start = offset;
        while (start > 0 && Character.isLetterOrDigit(text.charAt(start - 1))) {
            start--;
        }

        int end = offset;
        while (end < text.length() && Character.isLetterOrDigit(text.charAt(end))) {
            end++;
        }

        if (start == end) {
            end = Math.min(text.length(), end + 1); // select at least one char if not on a word
        }

        return new int[]{start, end};
    }

    public void clearSelection() {
        if (!isSelectionActive) return;
        isSelectionActive = false;
        selectionStart = -1;
        selectionEnd = -1;
        if (actionMode != null) {
            actionMode.finish();
            actionMode = null;
        }
        stopAutoScrolling();
        editor.refreshVisibleLineEditors(true);
        editor.invalidate(); // Request redraw to remove handles/highlights
    }

    private void stopAutoScrolling() {
        isAutoScrolling = false;
        editor.removeCallbacks(autoScrollRunnable);
    }

    private void startAutoScrolling(int dy) {
        if (!isAutoScrolling || autoScrollDy != dy) {
            autoScrollDy = dy;
            if (!isAutoScrolling) {
                isAutoScrolling = true;
                editor.postOnAnimation(autoScrollRunnable);
            }
        }
    }

    private void checkAndHandleAutoScroll(float y) {
        int height = editor.getHeight();
        if (y < SCROLL_THRESHOLD) {
            // Scroll up
            int speed = (int) (MAX_SCROLL_SPEED * (SCROLL_THRESHOLD - y) / SCROLL_THRESHOLD);
            startAutoScrolling(-Math.max(1, speed));
        } else if (y > height - SCROLL_THRESHOLD) {
            // Scroll down
            int speed = (int) (MAX_SCROLL_SPEED * (y - (height - SCROLL_THRESHOLD)) / SCROLL_THRESHOLD);
            startAutoScrolling(Math.max(1, speed));
        } else {
            stopAutoScrolling();
        }
    }

    private void updateSelectionFromTouch(float x, float y) {
        lastTouchX = x;
        lastTouchY = y;
        int newOffset = getGlobalOffsetAtPosition(x, y);
        if (newOffset != -1) {
            if (draggingStartHandle) {
                selectionStart = newOffset;
            } else if (draggingEndHandle) {
                selectionEnd = newOffset;
            } else {
                // If not explicitly dragging a handle, just drag to select end
                selectionEnd = newOffset;
            }
            editor.invalidate(); // ONLY invalidate to redraw highlight/handles, don't refresh entire RecyclerView items for performance
        }
    }

    private boolean isPointNearHandle(float x, float y, float[] handleCoords) {
        if (handleCoords == null) return false;
        float hx = handleCoords[0];
        float hy = handleCoords[1] + handleRadius; // center of the circle part

        float dx = x - hx;
        float dy = y - hy;
        return (dx*dx + dy*dy) <= (TOUCH_SLOP_RADIUS * TOUCH_SLOP_RADIUS);
    }

    /**
     * Map a raw touch coordinate to a global character offset in the entire document.
     */
    private int getGlobalOffsetAtPosition(float x, float y) {
        View child = editor.findChildViewUnder(x, y);
        if (child != null) {
            RecyclerView.ViewHolder holder = editor.getChildViewHolder(child);
            if (holder != null) { // It's LineViewHolder, but package-private in RecyclerTextEditor
                // Since LineViewHolder and its inner views are not exposed,
                // we search for the TextView/EditText inside the child view.
                if (child instanceof TextView) {
                    TextView textView = (TextView) child;

                    // Convert raw coordinates to relative coordinates inside the TextView
                    int[] location = new int[2];
                    textView.getLocationOnScreen(location);
                    int[] editorLocation = new int[2];
                    editor.getLocationOnScreen(editorLocation);
                    float touchX = x - location[0] + editorLocation[0];
                    float touchY = y - location[1] + editorLocation[1];

                    int lineOffset = textView.getOffsetForPosition(touchX, touchY);

                    int adapterPosition = holder.getBindingAdapterPosition();
                    if (adapterPosition != RecyclerView.NO_POSITION) {
                        return editor.lineColToGlobalOffset(adapterPosition, lineOffset);
                    }
                }
            }
        }
        return -1;
    }

    @Override
    public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
        gestureDetector.onTouchEvent(e);

        if (isSelectionActive) {
            float x = e.getX();
            float y = e.getY();

            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                float[] startCoords = getCoordinatesForOffset(selectionStart);
                float[] endCoords = getCoordinatesForOffset(selectionEnd);

                draggingStartHandle = isPointNearHandle(x, y, startCoords);
                draggingEndHandle = isPointNearHandle(x, y, endCoords);

                if (draggingStartHandle || draggingEndHandle) {
                    return true; // Intercept to start dragging handle
                } else {
                    // Touched outside handles while active -> dismiss selection
                    clearSelection();
                    return false;
                }
            }
            return true; // Intercept all touches while selection active to prevent native scroll/click if not dragging handle
        }
        return false;
    }

    @Override
    public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
        if (!isSelectionActive) return;

        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            // Handled in onInterceptTouchEvent mostly
        } else if (e.getAction() == MotionEvent.ACTION_MOVE) {
            if (draggingStartHandle || draggingEndHandle) {
                updateSelectionFromTouch(e.getX(), e.getY());
                checkAndHandleAutoScroll(e.getY());
            }
        } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
            stopAutoScrolling();
            if (draggingStartHandle || draggingEndHandle) {
                editor.refreshVisibleLineEditors(true); // Finalize the selection text refresh
            }
            draggingStartHandle = false;
            draggingEndHandle = false;
            lastTouchX = -1;
            lastTouchY = -1;
        }
    }

    @Override
    public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        // Handle parent scroll disallow if needed
    }

    private float[] getCoordinatesForOffset(int globalOffset) {
        if (editor == null) return null;

        int[] lineCol = editor.globalOffsetToLineCol(globalOffset);
        int lineIndex = lineCol[0];
        int colIndex = lineCol[1];

        RecyclerView.ViewHolder holder = editor.findViewHolderForAdapterPosition(lineIndex);
        if (holder != null && holder.itemView instanceof TextView) {
            TextView textView = (TextView) holder.itemView;
            Layout layout = textView.getLayout();
            if (layout != null) {
                // Determine line inside the TextView (since wrap is possible)
                int innerLine = layout.getLineForOffset(colIndex);
                float x = layout.getPrimaryHorizontal(colIndex);
                float y = layout.getLineBottom(innerLine); // bottom of the text line

                // Add padding and scroll offsets
                x += textView.getPaddingLeft();
                y += textView.getPaddingTop();

                // Convert relative to TextView to relative to RecyclerView
                int[] rvLocation = new int[2];
                editor.getLocationOnScreen(rvLocation);

                int[] tvLocation = new int[2];
                textView.getLocationOnScreen(tvLocation);

                float finalX = x + tvLocation[0] - rvLocation[0];
                float finalY = y + tvLocation[1] - rvLocation[1];

                return new float[]{finalX, finalY};
            }
        }
        return null;
    }

    @Override
    public void onDrawOver(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        if (!hasSelection()) {
            return;
        }

        int start = getSelectionStart();
        int end = getSelectionEnd();

        float[] startCoords = getCoordinatesForOffset(start);
        float[] endCoords = getCoordinatesForOffset(end);

        if (startCoords != null) {
            // Draw start handle (left pointing teardrop or simple circle+line)
            c.drawRect(startCoords[0] - handleWidth/2, startCoords[1] - handleRadius * 2, startCoords[0] + handleWidth/2, startCoords[1], handlePaint);
            c.drawCircle(startCoords[0], startCoords[1] + handleRadius, handleRadius, handlePaint);
        }

        if (endCoords != null) {
            // Draw end handle
            c.drawRect(endCoords[0] - handleWidth/2, endCoords[1] - handleRadius * 2, endCoords[0] + handleWidth/2, endCoords[1], handlePaint);
            c.drawCircle(endCoords[0], endCoords[1] + handleRadius, handleRadius, handlePaint);
        }
    }

    /**
     * Start the custom selection action mode.
     */
    public void startActionMode() {
        if (actionMode != null) {
            return;
        }

        actionMode = editor.startActionMode(new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                // Populate the menu with standard text operations
                menu.add(Menu.NONE, android.R.id.copy, 0, android.R.string.copy)
                    .setAlphabeticShortcut('c')
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                menu.add(Menu.NONE, android.R.id.cut, 1, android.R.string.cut)
                    .setAlphabeticShortcut('x')
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                menu.add(Menu.NONE, android.R.id.paste, 2, android.R.string.paste)
                    .setAlphabeticShortcut('v')
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                menu.add(Menu.NONE, android.R.id.selectAll, 3, android.R.string.selectAll)
                    .setAlphabeticShortcut('a')
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                int itemId = item.getItemId();
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);

                if (itemId == android.R.id.copy) {
                    CharSequence selectedText = editor.getText().subSequence(getSelectionStart(), getSelectionEnd());
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("text", selectedText));
                    }
                    clearSelection();
                    return true;
                } else if (itemId == android.R.id.cut) {
                    CharSequence selectedText = editor.getText().subSequence(getSelectionStart(), getSelectionEnd());
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("text", selectedText));
                    }

                    int start = getSelectionStart();
                    int end = getSelectionEnd();
                    Editable text = editor.getText();

                    editor.withAutoFormatDisabled(() -> {
                        text.replace(start, end, "");
                    });

                    clearSelection();
                    return true;
                } else if (itemId == android.R.id.paste) {
                    if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
                        CharSequence pasteText = clipboard.getPrimaryClip().getItemAt(0).coerceToText(context);
                        int start = getSelectionStart();
                        int end = getSelectionEnd();
                        Editable text = editor.getText();

                        editor.withAutoFormatDisabled(() -> {
                            text.replace(start, end, pasteText);
                        });
                    }
                    clearSelection();
                    return true;
                } else if (itemId == android.R.id.selectAll) {
                    selectionStart = 0;
                    selectionEnd = editor.length();
                    editor.refreshVisibleLineEditors(true);
                    editor.invalidate();
                    return true;
                }
                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                clearSelection();
                actionMode = null;
            }
        });
    }
}
