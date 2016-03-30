/*
 * Copyright © 2013 – 2015 Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package at.bitfire.davdroid;

import android.support.annotation.NonNull;
import android.text.TextUtils;

import org.w3c.dom.Text;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ezvcard.util.StringUtils;
import okhttp3.HttpUrl;

public class DavUtils {

    public static String ARGBtoCalDAVColor(int colorWithAlpha) {
        byte alpha = (byte)(colorWithAlpha >> 24);
        int color = colorWithAlpha & 0xFFFFFF;
        return String.format("#%06X%02X", color, alpha);
    }

    public static String lastSegmentOfUrl(@NonNull String url) {
        // the list returned by HttpUrl.pathSegments() is unmodifiable, so we have to create a copy
        List<String> segments = new LinkedList<>(HttpUrl.parse(url).pathSegments());
        Collections.reverse(segments);

        for (String segment : segments)
            if (!TextUtils.isEmpty(segment))
                return segment;

        return "/";
    }

}
