/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.launcher3.util;

import android.content.Context;
import android.graphics.Canvas;
import android.view.HapticFeedbackConstants;
import android.widget.EdgeEffect;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.LauncherPrefs;

public class SpringEdgeEffectFactory extends RecyclerView.EdgeEffectFactory {

    @NonNull
    @Override
    protected EdgeEffect createEdgeEffect(@NonNull RecyclerView recyclerView, int direction) {
        return new SpringEdgeEffect(recyclerView, direction);
    }

    public static EdgeEffect createSpringEdgeEffect(RecyclerView recyclerView, int direction) {
        return new SpringEdgeEffect(recyclerView, direction);
    }

    private static class SpringEdgeEffect extends EdgeEffect {

        private final RecyclerView mRecyclerView;
        private final SpringAnimation mSpringAnimation;
        private final boolean mIsVertical;
        private final float mMaxStretchFraction;
        private final float mVelocityScale;
        private final float mSign;
        private float mPullDistance = 0f;

        SpringEdgeEffect(RecyclerView recyclerView, int direction) {
            super(recyclerView.getContext());
            mRecyclerView = recyclerView;
            mIsVertical = direction == DIRECTION_TOP || direction == DIRECTION_BOTTOM;
            mSign = (direction == DIRECTION_TOP || direction == DIRECTION_LEFT) ? 1f : -1f;

            Context context = recyclerView.getContext();
            float stiffness = LauncherPrefs.OVERSCROLL_STIFFNESS.get(context);
            float dampingRatio = LauncherPrefs.OVERSCROLL_DAMPING_RATIO.get(context) / 100f;
            mMaxStretchFraction = LauncherPrefs.OVERSCROLL_MAX_STRETCH.get(context) / 100f;
            mVelocityScale = LauncherPrefs.OVERSCROLL_VELOCITY_SCALE.get(context) / 100f;

            DynamicAnimation.ViewProperty property =
                    mIsVertical ? DynamicAnimation.TRANSLATION_Y : DynamicAnimation.TRANSLATION_X;

            mSpringAnimation = new SpringAnimation(recyclerView, property, 0f);
            mSpringAnimation.setSpring(new SpringForce(0f)
                    .setStiffness(stiffness)
                    .setDampingRatio(dampingRatio));
        }

        @Override
        public void onPull(float deltaDistance) {
            onPull(deltaDistance, 0.5f);
        }

        @Override
        public void onPull(float deltaDistance, float displacement) {
            if (mSpringAnimation.isRunning()) {
                mSpringAnimation.cancel();
            }
            mPullDistance += deltaDistance;
            float size = mIsVertical ? mRecyclerView.getHeight() : mRecyclerView.getWidth();
            float maxStretch = size * mMaxStretchFraction;
            float translation = Math.max(-maxStretch,
                    Math.min(maxStretch, mSign * mPullDistance * size));

            if (mIsVertical) {
                mRecyclerView.setTranslationY(translation);
            } else {
                mRecyclerView.setTranslationX(translation);
            }
        }

        @Override
        public void onRelease() {
            if (mPullDistance != 0f) {
                mPullDistance = 0f;
                mRecyclerView.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                mSpringAnimation.animateToFinalPosition(0f);
            }
        }

        @Override
        public void onAbsorb(int velocity) {
            mRecyclerView.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            mSpringAnimation.setStartVelocity(mSign * velocity * mVelocityScale);
            mSpringAnimation.animateToFinalPosition(0f);
        }

        @Override
        public boolean draw(Canvas canvas) {
            return false;
        }

        @Override
        public boolean isFinished() {
            return !mSpringAnimation.isRunning();
        }
    }
}
