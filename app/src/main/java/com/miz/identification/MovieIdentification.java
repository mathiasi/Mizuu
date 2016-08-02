/*
 * Copyright (C) 2014 Michell Bak
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

package com.miz.identification;

import android.content.Context;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.SparseBooleanArray;

import com.miz.apis.tmdb.TmdbApi;
import com.miz.apis.tmdb.TmdbApiService;
import com.miz.apis.tmdb.models.TmdbConfiguration;
import com.miz.apis.tmdb.models.TmdbMovie;
import com.miz.apis.tmdb.models.TmdbMovieResult;
import com.miz.db.DbAdapterMovieMappings;
import com.miz.db.DbAdapterMovies;
import com.miz.functions.MizLib;
import com.miz.functions.MovieLibraryUpdateCallback;
import com.miz.mizuu.MizuuApplication;
import com.miz.utils.FileUtils;
import com.miz.utils.LocalBroadcastUtils;
import com.miz.utils.MovieDatabaseUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.miz.functions.PreferenceKeys.LANGUAGE_PREFERENCE;

public class MovieIdentification {

    private final MovieLibraryUpdateCallback mCallback;
    private final Context mContext;
    private final ArrayList<MovieStructure> mMovieStructures;
    private final TmdbConfiguration mTmdbConfiguration;

    private SparseBooleanArray mImdbMap = new SparseBooleanArray();
    private String mLocale = null;
    private boolean mCancel = false;
    private int mCurrentMovieId, mMovieId = 0, mCount = 0;

    public MovieIdentification(Context context, MovieLibraryUpdateCallback callback, ArrayList<MovieStructure> files, TmdbConfiguration tmdbConfiguration) {
        mContext = context;
        mCallback = callback;
        mMovieStructures = new ArrayList<>(files);
        mTmdbConfiguration = tmdbConfiguration;

        // Get the language preference
        getLanguagePreference();
    }

    private void getLanguagePreference() {
        mLocale = PreferenceManager.getDefaultSharedPreferences(mContext).getString(LANGUAGE_PREFERENCE, "en");
    }

    /**
     * Use this to disable movie searching
     * and attempt identification based on the
     * provided movie ID.
     * @param movieId
     */
    public void setMovieId(int movieId) {
        mMovieId = movieId;
    }

    public void setCurrentMovieId(int oldMovieId) {
        if (oldMovieId != DbAdapterMovies.UNIDENTIFIED_ID) {
            mCurrentMovieId = oldMovieId;
        }
    }

    private boolean overrideMovieId() {
        return getMovieId() > 0;
    }

    private int getMovieId() {
        return mMovieId;
    }

    private int getCurrentMovieId() {
        return mCurrentMovieId;
    }

    /**
     * Accepts two-letter ISO 639-1 language codes, i.e. "en".
     * @param language
     */
    public void setLanguage(String language) {
        mLocale = language;
    }

    public void cancel() {
        mCancel = true;
    }

    public void start() {
        // Go through all files
        for (int i = 0; i < mMovieStructures.size(); i++) {
            if (mCancel)
                return;

            MovieStructure ms = mMovieStructures.get(i);

            mImdbMap.put(i, ms.hasImdbId());
        }

        String apiKey = MizLib.getTmdbApiKey(mContext);
        TmdbApiService service = TmdbApi.getInstance();

        for (MovieStructure ms : mMovieStructures) {
            if (mCancel)
                return;

            mCount++;

            TmdbMovie movie = null;
            List<TmdbMovieResult> results = new ArrayList<>();

            if (!overrideMovieId()) {
                // Check if there's an IMDb ID and attempt to search based on it
                if (ms.hasImdbId()) {
                    try {
                        results = service.find(ms.getImdbId(), apiKey, "imdb_id", null).execute()
                                .body().getMovieResults();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                // If there's no results, attempt to search based on the movie file name and year
                if (results.size() == 0) {
                    int year = ms.getReleaseYear();
                    if (year >= 0) {
                        try {
                            results = service.search(apiKey, ms.getDecryptedFilename(), null,
                                    null, null, year, null).execute().body().getResults();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }

                // If there's still no results, attempt to search based on the movie file name without year
                if (results.size() == 0) {
                    try {
                        results = service.search(apiKey, ms.getDecryptedFilename(), null,
                                null, null, null, null).execute().body().getResults();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                // If there's still no results, attempt to search based on the parent folder name and year
                if (results.size() == 0) {
                    int year = ms.getReleaseYear();
                    if (year >= 0) {
                        try {
                            results = service.search(apiKey, ms.getDecryptedParentFolderName(), null,
                                    null, null, year, null).execute().body().getResults();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }

                // If there's still no results, search based on the parent folder name only
                if (results.size() == 0) {
                    try {
                        results = service.search(apiKey, ms.getDecryptedParentFolderName(), null,
                                null, null, null, null).execute().body().getResults();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            } else {
                try {
                    movie = service.getMovie(getMovieId(), apiKey, mLocale, null).execute().body();

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            if (!overrideMovieId() && results.size() > 0) {
                // Automatic library update
                try {
                    movie = service.getFullMovie(results.get(0).getId(), apiKey, mLocale).execute().body();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            // Last check - is movie still null?
            if (movie == null)
                movie = new TmdbMovie();

            createMovie(ms, movie);
        }
    }

    private void createMovie(MovieStructure ms, TmdbMovie movie) {
        if (movie.getId() != DbAdapterMovies.UNIDENTIFIED_ID) {
            // Download the cover image and try again if it fails
            File movieThumb = FileUtils.getMovieThumb(mContext, movie.getId());
            if (!movieThumb.exists() && !TextUtils.isEmpty(movie.getPoster())) {
                String posterUrl = mTmdbConfiguration.getImages().getPosterUrl() + movie.getPoster();
                MizLib.downloadFile(posterUrl, movieThumb, true);
            }

            // Download the backdrop image and try again if it fails
            File movieBackdrop = FileUtils.getMovieBackdrop(mContext, movie.getId());
            if (!movieBackdrop.exists() && !TextUtils.isEmpty(movie.getBackdrop())) {
                String backdropUrl = mTmdbConfiguration.getImages().getBackdropUrl() + movie.getBackdrop();
                MizLib.downloadFile(backdropUrl, movieBackdrop, true);
            }

            // Download the collection image
            if (movie.getCollection() != null) {
                File collectionThumb = FileUtils.getMovieThumb(mContext, movie.getCollection().getId());
                if (!collectionThumb.exists() &&
                        !TextUtils.isEmpty(movie.getCollection().getPosterPath())) {
                    String collectionUrl = mTmdbConfiguration.getImages().getPosterUrl() +
                            movie.getCollection().getPosterPath();
                    MizLib.downloadFile(collectionUrl, collectionThumb, true);
                }
            }
        }

        addToDatabase(ms, movie);
    }

    private void addToDatabase(MovieStructure ms, TmdbMovie movie) {
        DbAdapterMovieMappings dbHelperMovieMapping = MizuuApplication.getMovieMappingAdapter();
        DbAdapterMovies dbHelper = MizuuApplication.getMovieAdapter();

        // Check if this is manual identification by the user
        if (overrideMovieId()) {

            // How many filepaths are mapped to the current movie ID?
            int currentCount = dbHelperMovieMapping.getMovieFilepaths(getCurrentMovieId()).size();

            if (currentCount > 1) {
                // This movie has more than one filepath mapping, so we don't want
                // to remove the movie entry nor any images, etc.

                // Update the ID currently used to map the filepath to the movie
                dbHelperMovieMapping.updateTmdbId(ms.getFilepath(), getCurrentMovieId(), String.valueOf(getMovieId()));
            } else {
                if (getCurrentMovieId() == DbAdapterMovies.UNIDENTIFIED_ID) {
                    // We're dealing with an unidentified movie, so we update
                    // the mapped TMDb ID of the filepath to the new one
                    dbHelperMovieMapping.updateTmdbId(ms.getFilepath(), String.valueOf(getMovieId()));
                } else {
                    // This movie only has one filepath mapping, i.e. the one we're
                    // currently re-assigning. It's safe to delete all movie data and
                    // create the filepath mapping for the new movie ID.

                    // Delete the old movie and everything related to it
                    MovieDatabaseUtils.deleteMovie(mContext, getCurrentMovieId());

                    // Create the new filepath mapping
                    dbHelperMovieMapping.createFilepathMapping(ms.getFilepath(), String.valueOf(getMovieId()));
                }
            }

        } else {
            // This is Mizuu's automatic library update...

            // Just create the filepath mapping - if the filepath / movie
            // combination already exists, it won't do anything
            dbHelperMovieMapping.createFilepathMapping(ms.getFilepath(), String.valueOf(movie.getId()));
        }

        // Finally, create or update the movie
        dbHelper.createOrUpdateMovie(movie.getId(), movie.getTitle(), movie.getOverview(),
                movie.getImdbId(), String.valueOf(movie.getVoteAverage()), movie.getTagline(), movie.getReleaseDate(),
                "", String.valueOf(movie.getRuntime()), "", "", "0",
                "", movie.getCollection() == null ? "" : movie.getCollection().getName(),
                movie.getCollection() == null ? "" : String.valueOf(movie.getCollection().getId()),
                "0", "0", String.valueOf(System.currentTimeMillis()));

        performCallback(movie);
    }

    private void performCallback(TmdbMovie movie) {
        if (mCallback != null) {
            mCallback.onMovieAdded(movie.getTitle(), movie.getId(), mCount);
        }

        LocalBroadcastUtils.updateMovieLibrary(mContext);
    }
}