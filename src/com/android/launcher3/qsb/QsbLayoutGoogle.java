package com.android.launcher3.qsb;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.Reorderable;
import com.android.launcher3.Utilities;
import com.android.launcher3.graphics.ThemeManager;
import com.android.launcher3.util.MultiTranslateDelegate;

public class QsbLayoutGoogle extends FrameLayout
        implements Reorderable, SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "QsbLayoutGoogle";

    private ImageView mMicIcon;
    private ImageView mGoogleIcon;
    private ImageView mLensIcon;
    private ImageView mAiModeButton;
    private final Context mContext;

    private final MultiTranslateDelegate mTranslateDelegate = new MultiTranslateDelegate(this);
    private float mScaleForReorderBounce = 1f;
    private ThemeManager.ThemeChangeListener mThemeChangeListener;

    public QsbLayoutGoogle(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
    }

    public QsbLayoutGoogle(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mMicIcon = findViewById(R.id.mic_icon);
        mGoogleIcon = findViewById(R.id.g_icon);
        mLensIcon = findViewById(R.id.lens_icon);
        mAiModeButton = findViewById(R.id.ai_mode_button);

        setIcons();
        setupMicIcon();
        setupLensIcon();
        setupAiModeButton();

        mThemeChangeListener = this::setIcons;
        ThemeManager.INSTANCE.get(mContext).addChangeListener(mThemeChangeListener);
        LauncherPrefs.getPrefs(mContext).registerOnSharedPreferenceChangeListener(this);

        String searchPackage = QsbContainerView.getSearchWidgetPackageName(mContext);
        if (searchPackage != null) {
            setOnClickListener(v -> launchSearchActivity(searchPackage));
        }

        post(() -> {
            View parent = (View) getParent();
            if (parent != null) {
                parent.setBackground(new QsbOuterDrawable(mContext));
            }
        });
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width  = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child != null) {
                measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mThemeChangeListener != null) {
            ThemeManager.INSTANCE.get(mContext).removeChangeListener(mThemeChangeListener);
            mThemeChangeListener = null;
        }
        LauncherPrefs.getPrefs(mContext).unregisterOnSharedPreferenceChangeListener(this);
        setOnClickListener(null);
        if (mMicIcon != null) mMicIcon.setOnClickListener(null);
        if (mLensIcon != null) mLensIcon.setOnClickListener(null);
        if (mAiModeButton != null) mAiModeButton.setOnClickListener(null);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (key.equals(LauncherPrefs.DOCK_MUSIC_SEARCH.getSharedPrefKey())) {
            setIcons();
        }
    }

    private void setIcons() {
        boolean isThemed = LauncherPrefs.DOCK_THEME.get(getContext());
        boolean isMusicSearch = Utilities.isMusicSearchEnabled(mContext);

        mGoogleIcon.setImageResource(isThemed
                ? R.drawable.ic_super_g_themed
                : R.drawable.ic_super_g_color);

        if (mLensIcon != null) {
            mLensIcon.setImageResource(isThemed
                    ? R.drawable.ic_lens_themed_google
                    : R.drawable.ic_lens_color_google);
        }

        if (mMicIcon != null) {
            mMicIcon.setImageResource(isThemed
                    ? (isMusicSearch ? R.drawable.ic_music_themed : R.drawable.ic_mic_themed_google)
                    : (isMusicSearch ? R.drawable.ic_music_color : R.drawable.ic_mic_color_google));
        }

        if (mAiModeButton != null) {
            mAiModeButton.setImageResource(isThemed
                    ? R.drawable.ic_ai_mode_themed_google
                    : R.drawable.ic_ai_mode_color_google);
        }
    }

    private void setupMicIcon() {
        if (mMicIcon == null) return;
        mMicIcon.setOnClickListener(view -> {
            try {
                Intent intent = new Intent();
                if (Utilities.isMusicSearchEnabled(view.getContext())) {
                    intent.setAction("com.google.android.googlequicksearchbox.MUSIC_SEARCH");
                    intent.setPackage(QsbContainerView.getSearchWidgetPackageName(view.getContext()));
                } else {
                    intent.setAction("android.intent.action.VOICE_COMMAND");
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                view.getContext().startActivity(intent);
            } catch (Exception e) {
                mMicIcon.setVisibility(View.GONE);
                Log.e(TAG, "Mic icon launch failed", e);
            }
        });
    }

    private void setupLensIcon() {
        if (mLensIcon == null) return;
        if (!Utilities.isGSAEnabled(mContext)) {
            mLensIcon.setVisibility(View.GONE);
            return;
        }
        mLensIcon.setVisibility(View.VISIBLE);
        mLensIcon.setOnClickListener(v -> {
            try {
                mContext.startActivity(new Intent(Intent.ACTION_VIEW)
                        .setComponent(new ComponentName(
                                Utilities.GSA_PACKAGE, Utilities.LENS_ACTIVITY))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .setData(Uri.parse(Utilities.LENS_URI))
                        .putExtra("LensHomescreenShortcut", true));
            } catch (Exception e) {
                mLensIcon.setVisibility(View.GONE);
                Log.e(TAG, "Lens launch failed", e);
            }
        });
    }

    private void launchSearchActivity(String searchPackage) {
        try {
            mContext.startActivity(new Intent("android.search.action.GLOBAL_SEARCH")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .setPackage(searchPackage));
            return;
        } catch (ActivityNotFoundException e) {
            Log.d(TAG, "GLOBAL_SEARCH not found for " + searchPackage);
        }
        try {
            Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(searchPackage);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                mContext.startActivity(intent);
                return;
            }
        } catch (ActivityNotFoundException e) {
            Log.d(TAG, "Launch intent not found for " + searchPackage);
        }
        try {
            mContext.startActivity(new Intent(Intent.ACTION_VIEW)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .setPackage(searchPackage));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(mContext, R.string.activity_not_found, Toast.LENGTH_SHORT).show();
            Log.e(TAG, "No search activity found for: " + searchPackage);
        }
    }

    private void setupAiModeButton() {
        if (mAiModeButton == null) return;
        mAiModeButton.setOnClickListener(v -> {
            if (Utilities.isAiMusicSearchEnabled(mContext)) {
                launchAiMusicSearch();
                return;
            }

            String[] aiActivities = {
                "com.google.android.googlequicksearchbox.OneSearchAimActivity",
                "com.google.android.googlequicksearchbox.GeminiGatewayActivity",
                "com.google.android.googlequicksearchbox.SearchActivity",
                "com.google.android.googlequicksearchbox.VoiceSearchActivity",
                "com.google.android.googlequicksearchbox.OneSearchActivity",
                "com.google.android.googlequicksearchbox.GoogleAppVoiceAssistEntrypoint"
            };

            for (String activityName : aiActivities) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW)
                            .setComponent(new ComponentName(Utilities.GSA_PACKAGE, activityName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    mContext.startActivity(intent);
                    Log.d(TAG, "Successfully launched AI activity: " + activityName);
                    return;
                } catch (ActivityNotFoundException e) {
                    Log.d(TAG, "Activity not found: " + activityName);
                } catch (SecurityException e) {
                    Log.w(TAG, "Security exception: " + activityName + ", " + e.getMessage());
                }
            }

            String[] aiIntents = {
                "com.google.android.PIXEL_SEARCH",
                "android.intent.action.ASSIST",
                "android.intent.action.VOICE_ASSIST"
            };

            for (String intentAction : aiIntents) {
                try {
                    Intent intent = new Intent(intentAction)
                            .setPackage(Utilities.GSA_PACKAGE)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    mContext.startActivity(intent);
                    Log.d(TAG, "Launched via intent action: " + intentAction);
                    return;
                } catch (ActivityNotFoundException e) {
                    Log.d(TAG, "Intent action not found: " + intentAction);
                } catch (SecurityException e) {
                    Log.w(TAG, "Security exception: " + intentAction + ", " + e.getMessage());
                }
            }

            String searchPackage = QsbContainerView.getSearchWidgetPackageName(mContext);
            if (searchPackage != null) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VOICE_COMMAND)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            .setPackage(searchPackage);
                    mContext.startActivity(intent);
                    Log.d(TAG, "Falling back to voice command");
                } catch (ActivityNotFoundException e) {
                    Log.e(TAG, "No AI or voice activities found");
                    Toast.makeText(mContext, R.string.ai_mode_not_available, Toast.LENGTH_SHORT).show();
                } catch (SecurityException e) {
                    Log.e(TAG, "Security exception: " + e.getMessage());
                    Toast.makeText(mContext, R.string.ai_mode_not_available, Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(mContext, R.string.ai_mode_not_available, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void launchAiMusicSearch() {
        String searchPackage = QsbContainerView.getSearchWidgetPackageName(mContext);
        if (searchPackage == null) {
            Toast.makeText(mContext, R.string.ai_mode_not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN)
                    .setAction("com.google.android.googlequicksearchbox.MUSIC_SEARCH")
                    .setPackage(searchPackage)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            mContext.startActivity(intent);
            Log.d(TAG, "Successfully launched AI music search");
        } catch (ActivityNotFoundException e) {
            Log.d(TAG, "Music search not found, falling back to voice command");
            try {
                Intent intent = new Intent(Intent.ACTION_VOICE_COMMAND)
                        .setPackage(searchPackage)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                mContext.startActivity(intent);
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(mContext, R.string.ai_mode_not_available, Toast.LENGTH_SHORT).show();
            } catch (SecurityException e2) {
                Toast.makeText(mContext, R.string.ai_mode_not_available, Toast.LENGTH_SHORT).show();
            }
        } catch (SecurityException e) {
            Toast.makeText(mContext, R.string.ai_mode_not_available, Toast.LENGTH_SHORT).show();
        }
    }

    @Override public MultiTranslateDelegate getTranslateDelegate() { return mTranslateDelegate; }

    @Override
    public void setReorderBounceScale(float scale) {
        mScaleForReorderBounce = scale;
        super.setScaleX(scale);
        super.setScaleY(scale);
    }

    @Override public float getReorderBounceScale() { return mScaleForReorderBounce; }
}
