/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.settings;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.preference.PreferenceViewHolder;

import com.android.launcher3.R;
import com.android.launcher3.util.Themes;

/**
 * A preference that shows two tappable card views for switching between
 * default list view and Caddy (categorized folders) view.
 */
public class CaddyCardSelectorPreference extends androidx.preference.Preference {

    private FrameLayout mDefaultListCard;
    private FrameLayout mCaddyCard;
    private TextView mDefaultListLabel;
    private TextView mCaddyLabel;
    private View mDefaultListIllustration;
    private View mCaddyIllustration;
    private View mDefaultListSearchBar;
    private View mCaddySearchBar;
    private boolean mIsCaddyEnabled = false;
    private android.graphics.drawable.Drawable mDefaultBackground;
    private android.graphics.drawable.Drawable mSelectedBackground;

    public CaddyCardSelectorPreference(Context context) {
        super(context);
        init();
    }

    public CaddyCardSelectorPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CaddyCardSelectorPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setLayoutResource(R.layout.preference_caddy_card_selector);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        // Hide the default title since we're using PreferenceCategory
        View titleView = holder.findViewById(android.R.id.title);
        if (titleView != null) {
            titleView.setVisibility(View.GONE);
        }

        mDefaultListCard = (FrameLayout) holder.findViewById(R.id.default_list_card);
        mCaddyCard = (FrameLayout) holder.findViewById(R.id.caddy_card);
        mDefaultListLabel = (TextView) holder.findViewById(R.id.default_list_label);
        mCaddyLabel = (TextView) holder.findViewById(R.id.caddy_label);
        mDefaultListIllustration = holder.findViewById(R.id.default_list_illustration);
        mCaddyIllustration = holder.findViewById(R.id.caddy_illustration);
        
        // Find search bar views - they're direct children of the illustration LinearLayouts
        if (mDefaultListIllustration != null && mDefaultListIllustration instanceof android.view.ViewGroup) {
            android.view.ViewGroup defaultGroup = (android.view.ViewGroup) mDefaultListIllustration;
            if (defaultGroup.getChildCount() > 0) {
                mDefaultListSearchBar = defaultGroup.getChildAt(0);
            }
        }
        if (mCaddyIllustration != null && mCaddyIllustration instanceof android.view.ViewGroup) {
            android.view.ViewGroup caddyGroup = (android.view.ViewGroup) mCaddyIllustration;
            if (caddyGroup.getChildCount() > 0) {
                mCaddySearchBar = caddyGroup.getChildAt(0);
            }
        }

        // Create backgrounds for selected and unselected states
        createBackgrounds();

        // Load current preference value
        // drawer_list = true means default list, false means Caddy
        boolean drawerList = getSharedPreferences().getBoolean(getKey(), true);
        mIsCaddyEnabled = !drawerList;

        if (mDefaultListCard != null) {
            mDefaultListCard.setOnClickListener(v -> {
                if (!mIsCaddyEnabled) return; // Already selected
                setCaddyEnabled(false);
            });
        }

        if (mCaddyCard != null) {
            mCaddyCard.setOnClickListener(v -> {
                if (mIsCaddyEnabled) return; // Already selected
                setCaddyEnabled(true);
            });
        }

        updateCardSelection();
    }

    @Override
    protected void notifyChanged() {
        super.notifyChanged();
        // Rebind to update the visual state
        if (mDefaultListCard != null && mCaddyCard != null) {
            updateCardSelection();
        }
    }

    private void setCaddyEnabled(boolean enabled) {
        mIsCaddyEnabled = enabled;
        // Save preference: drawer_list = true means default list, false means Caddy
        getSharedPreferences().edit().putBoolean(getKey(), !enabled).apply();
        updateCardSelection();
        notifyChanged();
        
        // Notify preference change listener if available
        if (getOnPreferenceChangeListener() != null) {
            getOnPreferenceChangeListener().onPreferenceChange(this, !enabled);
        }
    }

    private void createBackgrounds() {
        Context context = getContext();
        int accentColor = Themes.getColorAccent(context);
        int backgroundColor = Themes.getColorBackground(context);
        int rippleColor = Themes.getAttrColor(context, android.R.attr.colorControlHighlight);
        
        float cornerRadius = 12f * context.getResources().getDisplayMetrics().density;
        float[] radii = new float[]{cornerRadius, cornerRadius, cornerRadius, cornerRadius,
                cornerRadius, cornerRadius, cornerRadius, cornerRadius};

        // Default background (unselected)
        GradientDrawable defaultShape = new GradientDrawable();
        defaultShape.setShape(GradientDrawable.RECTANGLE);
        defaultShape.setColor(backgroundColor);
        defaultShape.setCornerRadii(radii);
        
        ShapeDrawable defaultMask = new ShapeDrawable(new RoundRectShape(radii, null, null));
        defaultMask.getPaint().setColor(0xFFFFFFFF);
        
        mDefaultBackground = new RippleDrawable(
                ColorStateList.valueOf(rippleColor),
                defaultShape,
                defaultMask);

        // Selected background with accent color
        GradientDrawable selectedShape = new GradientDrawable();
        selectedShape.setShape(GradientDrawable.RECTANGLE);
        selectedShape.setColor(accentColor);
        selectedShape.setCornerRadii(radii);
        
        ShapeDrawable selectedMask = new ShapeDrawable(new RoundRectShape(radii, null, null));
        selectedMask.getPaint().setColor(0xFFFFFFFF);
        
        mSelectedBackground = new RippleDrawable(
                ColorStateList.valueOf(rippleColor),
                selectedShape,
                selectedMask);
    }

    /**
     * Calculate if a color is light or dark to determine contrasting text color.
     * Uses relative luminance formula from WCAG.
     */
    private boolean isColorLight(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;
        return luminance > 0.5;
    }

    /**
     * Darken a color by a specified factor (0.0 to 1.0).
     */
    private int darkenColor(int color, float factor) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] *= (1.0f - factor); // Reduce brightness
        return Color.HSVToColor(hsv);
    }

    /**
     * Apply color to all circles and search bar in an illustration view.
     */
    private void applyIllustrationColors(View illustrationView, View searchBar, int color) {
        if (illustrationView == null) return;

        // Set search bar color
        if (searchBar != null && searchBar.getBackground() != null) {
            android.graphics.drawable.Drawable bg = searchBar.getBackground();
            // Handle both GradientDrawable and ShapeDrawable (used in XML)
            if (bg instanceof android.graphics.drawable.GradientDrawable) {
                ((android.graphics.drawable.GradientDrawable) bg).setColor(color);
            } else if (bg instanceof android.graphics.drawable.ShapeDrawable) {
                ((android.graphics.drawable.ShapeDrawable) bg).getPaint().setColor(color);
            } else {
                // If it's a different type or not mutable, create a new drawable
                android.graphics.drawable.GradientDrawable newBg = new android.graphics.drawable.GradientDrawable();
                newBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                newBg.setColor(color);
                float cornerRadius = 16f * searchBar.getContext().getResources().getDisplayMetrics().density;
                newBg.setCornerRadius(cornerRadius);
                searchBar.setBackground(newBg);
            }
            searchBar.invalidate();
        }

        // Apply color to all circle views recursively
        if (illustrationView instanceof android.view.ViewGroup) {
            applyCircleColorRecursive((android.view.ViewGroup) illustrationView, color);
        }
    }

    /**
     * Recursively find and color all circle drawables in the view hierarchy.
     */
    private void applyCircleColorRecursive(android.view.ViewGroup parent, int color) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getBackground() != null) {
                android.graphics.drawable.Drawable bg = child.getBackground();
                // Handle both GradientDrawable and ShapeDrawable (used in XML)
                if (bg instanceof android.graphics.drawable.GradientDrawable) {
                    ((android.graphics.drawable.GradientDrawable) bg).setColor(color);
                    child.invalidate();
                } else if (bg instanceof android.graphics.drawable.ShapeDrawable) {
                    ((android.graphics.drawable.ShapeDrawable) bg).getPaint().setColor(color);
                    child.invalidate();
                } else {
                    // If it's a different type or not mutable, create a new circle drawable
                    android.graphics.drawable.GradientDrawable newBg = new android.graphics.drawable.GradientDrawable();
                    newBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                    newBg.setColor(color);
                    child.setBackground(newBg);
                }
            }
            if (child instanceof android.view.ViewGroup) {
                applyCircleColorRecursive((android.view.ViewGroup) child, color);
            }
        }
    }

    private void updateCardSelection() {
        if (mDefaultListCard == null || mCaddyCard == null) {
            return;
        }

        Context context = getContext();
        int accentColor = Themes.getColorAccent(context);
        int textColorNormal = Themes.getAttrColor(context, android.R.attr.textColorPrimary);
        
        // Calculate contrasting text color for accent background
        // Use white for dark accents, black for light accents
        int textColorOnAccent = isColorLight(accentColor) ? Color.BLACK : Color.WHITE;

        // Use specific grey colors for illustrations:
        // Selected (enabled): very dark grey
        // Unselected (disabled): light grey
        int selectedIllustrationColor = Color.parseColor("#424242"); // Very dark grey
        int unselectedIllustrationColor = Color.parseColor("#BDBDBD"); // Light grey

        // Update card appearance based on selection
        // Both illustrations remain at full opacity (like Lawnchair)
        // Only background and text color change to indicate selection
        // Illustration elements (circles and search bar) use darker colors, with selected being darker

        if (mIsCaddyEnabled) {
            // Caddy is selected
            if (mSelectedBackground != null) {
                mCaddyCard.setBackground(mSelectedBackground);
            }
            if (mDefaultBackground != null) {
                mDefaultListCard.setBackground(mDefaultBackground);
            }
            if (mCaddyLabel != null) {
                mCaddyLabel.setTextColor(textColorOnAccent);
            }
            if (mCaddyIllustration != null) {
                mCaddyIllustration.setAlpha(1.0f);
                // Apply darker color for selected illustration
                applyIllustrationColors(mCaddyIllustration, mCaddySearchBar, selectedIllustrationColor);
            }
            if (mDefaultListLabel != null) {
                mDefaultListLabel.setTextColor(textColorNormal);
            }
            if (mDefaultListIllustration != null) {
                mDefaultListIllustration.setAlpha(1.0f);
                // Apply lighter (but still darker than before) color for unselected illustration
                applyIllustrationColors(mDefaultListIllustration, mDefaultListSearchBar, unselectedIllustrationColor);
            }
        } else {
            // Default list is selected
            if (mSelectedBackground != null) {
                mDefaultListCard.setBackground(mSelectedBackground);
            }
            if (mDefaultBackground != null) {
                mCaddyCard.setBackground(mDefaultBackground);
            }
            if (mDefaultListLabel != null) {
                mDefaultListLabel.setTextColor(textColorOnAccent);
            }
            if (mDefaultListIllustration != null) {
                mDefaultListIllustration.setAlpha(1.0f);
                // Apply darker color for selected illustration
                applyIllustrationColors(mDefaultListIllustration, mDefaultListSearchBar, selectedIllustrationColor);
            }
            if (mCaddyLabel != null) {
                mCaddyLabel.setTextColor(textColorNormal);
            }
            if (mCaddyIllustration != null) {
                mCaddyIllustration.setAlpha(1.0f);
                // Apply lighter (but still darker than before) color for unselected illustration
                applyIllustrationColors(mCaddyIllustration, mCaddySearchBar, unselectedIllustrationColor);
            }
        }
    }
}
