package io.r_a_d.radio;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.annotation.RequiresApi;
import android.support.design.widget.TabLayout;
import android.support.v4.content.res.ResourcesCompat;
import android.support.v4.graphics.drawable.RoundedBitmapDrawable;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v4.view.ViewPager;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.app.MediaRouteButton;
import android.text.Html;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.URLUtil;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadOptions;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaTrack;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.Session;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ActivityMain extends AppCompatActivity implements ViewPager.OnPageChangeListener{

    private final Integer API_FETCH_INTERVAL = 10000;
    private final Integer UPDATE_SONGTIME_INTERVAL = 500;
    private final String MAIN_API = "https://r-a-d.io/api";
    private final String DJIMAGE_API = "https://r-a-d.io/api/dj-image/";
    private final String NEWS_API = "https://r-a-d.io/api/news/";
    private final String SEARCH_API = "https://r-a-d.io/api/search/%1s?page=%2$d";
    private final Object lock = new Object();

    private boolean songChanged = false;
    private boolean firstSearchClick = true;
    private boolean sendBluetoothMeta = false;
    private boolean newsSet = false;
    private ViewPager viewPager;
    private JSONScraperTask jsonTask = new JSONScraperTask(this, 0);
    private DJImageTask djimageTask = new DJImageTask(this);
    private String current_dj_image;
    public JSONObject current_ui_json;
    private ScheduledExecutorService scheduledTaskExecutor;
    private HashMap<String, Integer> songTimes;
    private Requestor mRequestor;
    private View searchFooter;
    private AudioManager am;
    private MediaSessionCompat mMediaSession;
    // Chromecast
    private CastContext castContext;
    private MediaRouteButton mMediaRouteButton;
    private CastSession mCastSession;
    private SessionManager mSessionManager;
    private SessionManagerListener<CastSession> mSessionManagerListener;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.AppTheme);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.homescreen);

        songTimes = new HashMap<>();

        viewPager = (ViewPager) findViewById(R.id.viewpager);
        viewPager.setAdapter(new CustomPagerAdapter(this));
        viewPager.setOffscreenPageLimit(3);

        viewPager.addOnPageChangeListener(this);

        TabLayout tabLayout = (TabLayout) findViewById(R.id.tab_dots);
        tabLayout.setupWithViewPager(viewPager, true);

        scrapeNews(NEWS_API);

        scheduledTaskExecutor = Executors.newSingleThreadScheduledExecutor();

        scheduledTaskExecutor.scheduleWithFixedDelay(new Runnable(){
            public void run(){
                scrapeJSON(MAIN_API);
            }
        }, 0, API_FETCH_INTERVAL, TimeUnit.MILLISECONDS);

        scheduledTaskExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                calculateSongTimes();
            }
        }, 0, UPDATE_SONGTIME_INTERVAL, TimeUnit.MILLISECONDS);

        mRequestor = new Requestor(this);

        am = (AudioManager)getSystemService(Context.AUDIO_SERVICE);

        // This stuff is for allowing bluetooth tags
        mMediaSession = new MediaSessionCompat(this, "RadioMediaSession");
        mMediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mMediaSession.setActive(true);
        // Chromecast Junk
        initSessionManagerListener();
        castContext = CastContext.getSharedInstance(this);
        mSessionManager = castContext.getSessionManager();
        mSessionManager.addSessionManagerListener(mSessionManagerListener, CastSession.class);
        mMediaRouteButton = (MediaRouteButton) findViewById(R.id.media_route_button);
        CastButtonFactory.setUpMediaRouteButton(getApplicationContext(), mMediaRouteButton);
    }

    private void maybeUpdateBluetooth() {
        String title = "";
        String artist = "";
        Integer length, position;
        String np = PlayerState.NOW_PLAYING;
        int hyphenPos = np.indexOf(" - ");

        synchronized (lock){
            if(songTimes.containsKey("length"))
                length = songTimes.get("length");
            else
                length = 0;

            if(songTimes.containsKey("position"))
                position = songTimes.get("position");
            else
                position = 0;
        }

        if (hyphenPos == -1) {
            title = np;
        } else {
            try {
                title = URLDecoder.decode(np.substring(hyphenPos + 3), "UTF-8");
                artist = URLDecoder.decode(np.substring(0, hyphenPos), "UTF-8");
            } catch (Exception e) {
            }
        }

        if (am.isBluetoothA2dpOn()) {
            MediaMetadataCompat metadata = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, artist)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, length)
                    .build();

            mMediaSession.setMetadata(metadata);

            PlaybackStateCompat state = new PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY)
                    .setState(PlaybackStateCompat.STATE_PLAYING, position, 1.0f, SystemClock.elapsedRealtime())
                    .build();

            mMediaSession.setPlaybackState(state);
        }

        Intent i = new Intent("com.android.music.metachanged");
        i.putExtra("artist", artist);
        i.putExtra("track", title);
        i.putExtra("duration", length);
        i.putExtra("position", position);
        sendBroadcast(i);
    }

    @Override
    public void onBackPressed() {
        if(viewPager.getCurrentItem() == 0) {
            if(isDrawerVisible(findViewById(android.R.id.content))) {
                closeSideDrawer();
            } else {
                super.onBackPressed();
            }
        } else {
            viewPager.setCurrentItem(0, true);
        }
    }

    @Override protected void onPause() {
        super.onPause();
        //mSessionManager.removeSessionManagerListener(mSessionManagerListener);
        mCastSession = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        scheduledTaskExecutor.shutdownNow();

        mMediaSession.release();
    }
    @Override
    protected void onResume() {
        super.onResume();
        scrapeJSON(MAIN_API);
        mCastSession = mSessionManager.getCurrentCastSession();
        //mSessionManager.addSessionManagerListener(mSessionManagerListener);
    }

    @Override
    public void onPageSelected(int position) {
        TextView title_text = (TextView)findViewById(R.id.radio);
        View page = viewPager.getChildAt(position);

        View curView = getCurrentFocus();
        if(curView != null)
        {
            InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(curView.getWindowToken(), 0);
        }

        int pageID = R.id.now_playing_page;

        if(page != null)
            pageID = page.getId();

        switch (pageID){
            case R.id.now_playing_page:
                title_text.setText(R.string.app_name);
                break;
            case R.id.requests_page:
                title_text.setText(R.string.request_page);
                break;
            case R.id.news_page:
                title_text.setText(R.string.news_page);
                if(!newsSet)
                    scrapeNews(NEWS_API);
                break;
        }
    }

    @Override
    public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
        return;
    }

    @Override
    public void onPageScrollStateChanged(int state) {
        return;
    }

    public void openThread(View v) {
        try {
            if (current_ui_json != null) {
                String threadurl = current_ui_json.getString("thread");
                if(!threadurl.isEmpty() && !current_ui_json.getBoolean("isafkstream") && URLUtil.isValidUrl(threadurl)) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(threadurl)));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void updateUI(){
        try {
            View now_playing = viewPager.getChildAt(0);
            TextView lp4 = (TextView)findViewById(android.R.id.content).findViewById((R.id.left_drawer)).findViewById(R.id.lp4);
            TextView lp3 = (TextView)findViewById(android.R.id.content).findViewById((R.id.left_drawer)).findViewById(R.id.lp3);
            TextView lp2 = (TextView)findViewById(android.R.id.content).findViewById((R.id.left_drawer)).findViewById(R.id.lp2);
            TextView lp1 = (TextView)findViewById(android.R.id.content).findViewById((R.id.left_drawer)).findViewById(R.id.lp1);
            TextView lp0 = (TextView)findViewById(android.R.id.content).findViewById((R.id.left_drawer)).findViewById(R.id.lp0);
            TextView q1 = (TextView)findViewById(android.R.id.content).findViewById((R.id.left_drawer)).findViewById(R.id.q1);
            TextView q2 = (TextView)findViewById(android.R.id.content).findViewById((R.id.left_drawer)).findViewById(R.id.q2);
            TextView q3 = (TextView)findViewById(android.R.id.content).findViewById((R.id.left_drawer)).findViewById(R.id.q3);
            TextView q4 = (TextView)findViewById(android.R.id.content).findViewById((R.id.left_drawer)).findViewById(R.id.q4);
            TextView q5 = (TextView)findViewById(android.R.id.content).findViewById((R.id.left_drawer)).findViewById(R.id.q5);
            TextView threadtxt = (TextView)findViewById(android.R.id.content).findViewById((R.id.left_drawer)).findViewById(R.id.thread);
            JSONObject djdata = new JSONObject(current_ui_json.getString("dj"));
            JSONArray queue_list = current_ui_json.getJSONArray("queue");
            JSONArray last_played_list = current_ui_json.getJSONArray("lp");

            TextView np = (TextView)now_playing.findViewById(R.id.tags);
            String tags = current_ui_json.getString("np");

            String threadurl = current_ui_json.getString("thread");


            TextView ls = (TextView)now_playing.findViewById(R.id.listeners);
            String listeners = current_ui_json.getString("listeners");

            TextView dj_name = (TextView)now_playing.findViewById(R.id.dj_name);
            String djname = djdata.getString("djname");

            Integer song_start = current_ui_json.getInt("start_time");
            Integer song_end = current_ui_json.getInt("end_time");
            Integer song_length_position = current_ui_json.getInt("current") - song_start;


            //String[] djcolor = djdata.getString("djcolor").split(" ");
            //Integer djhex = Color.rgb(Integer.valueOf(djcolor[0]), Integer.valueOf(djcolor[1]), Integer.valueOf(djcolor[2]));

            TextView nextsong = (TextView)now_playing.findViewById(R.id.nextsong);
            String ns = queue_list.getJSONObject(0).getString("meta");

            String djimgid = djdata.getString("djimage");

            if(current_dj_image == null || !current_dj_image.equals(djimgid)) {
                current_dj_image = djimgid;
                scrapeDJImage(DJIMAGE_API + djimgid);
            }

            if(!threadurl.isEmpty() && !current_ui_json.getBoolean("isafkstream") && URLUtil.isValidUrl(threadurl)) {
                threadtxt.setText("Thread Up!");
                threadtxt.setTextColor(ResourcesCompat.getColor(getResources(), R.color.rblue, null));
            } else {
                threadtxt.setText("No Thread Up");
                threadtxt.setTextColor(ResourcesCompat.getColor(getResources(), R.color.dark, null));
            }

            if(!np.getText().toString().equals(tags)) {
                np.setText(tags);
                PlayerState.NOW_PLAYING = tags;
                synchronized (lock)
                {
                    songTimes.put("start", song_start);
                    songTimes.put("end", song_end);
                    songTimes.put("position", song_length_position * 1000);
                    songChanged = true;
                }
                //pb.setMax(song_length);
            }

            lp0.setText(last_played_list.getJSONObject(0).getString("meta"));
            lp1.setText(last_played_list.getJSONObject(1).getString("meta"));
            lp2.setText(last_played_list.getJSONObject(2).getString("meta"));
            lp3.setText(last_played_list.getJSONObject(3).getString("meta"));
            lp4.setText(last_played_list.getJSONObject(4).getString("meta"));

            if(current_ui_json.getBoolean("isafkstream")) {
                q1.setText(queue_list.getJSONObject(0).getString("meta"));
                if(queue_list.getJSONObject(0).getInt("type") == 1)
                    q1.setTextColor(ResourcesCompat.getColor(getResources(), R.color.bluereq, null));
                else
                    q1.setTextColor(ResourcesCompat.getColor(getResources(), R.color.whited, null));

                q2.setText(queue_list.getJSONObject(1).getString("meta"));
                if(queue_list.getJSONObject(1).getInt("type") == 1)
                    q2.setTextColor(ResourcesCompat.getColor(getResources(), R.color.bluereq, null));
                else
                    q2.setTextColor(ResourcesCompat.getColor(getResources(), R.color.whited2, null));

                q3.setText(queue_list.getJSONObject(2).getString("meta"));
                if(queue_list.getJSONObject(2).getInt("type") == 1)
                    q3.setTextColor(ResourcesCompat.getColor(getResources(), R.color.bluereq, null));
                else
                    q3.setTextColor(ResourcesCompat.getColor(getResources(), R.color.whited3, null));

                q4.setText(queue_list.getJSONObject(3).getString("meta"));
                if(queue_list.getJSONObject(3).getInt("type") == 1)
                    q4.setTextColor(ResourcesCompat.getColor(getResources(), R.color.bluereq, null));
                else
                    q4.setTextColor(ResourcesCompat.getColor(getResources(), R.color.whited4, null));

                q5.setText(queue_list.getJSONObject(4).getString("meta"));
                if(queue_list.getJSONObject(4).getInt("type") == 1)
                    q5.setTextColor(ResourcesCompat.getColor(getResources(), R.color.bluereq, null));
                else
                    q5.setTextColor(ResourcesCompat.getColor(getResources(), R.color.whited5, null));

                findViewById(android.R.id.content).findViewById((R.id.left_drawer)).findViewById(R.id.hide_view1).setVisibility(View.VISIBLE);
                findViewById(android.R.id.content).findViewById((R.id.left_drawer)).findViewById(R.id.hide_view2).setVisibility(View.VISIBLE);
                findViewById(android.R.id.content).findViewById((R.id.left_drawer)).findViewById(R.id.hide_view3).setVisibility(View.VISIBLE);
                findViewById(android.R.id.content).findViewById((R.id.left_drawer)).findViewById(R.id.hide_view4).setVisibility(View.VISIBLE);
            } else {
                q1.setText("No Queue");
                q1.setTextColor(ResourcesCompat.getColor(getResources(), R.color.dark, null));
                q2.setText("");
                q3.setText("");
                q4.setText("");
                q5.setText("");
                findViewById(android.R.id.content).findViewById((R.id.left_drawer)).findViewById(R.id.hide_view1).setVisibility(View.INVISIBLE);
                findViewById(android.R.id.content).findViewById((R.id.left_drawer)).findViewById(R.id.hide_view2).setVisibility(View.INVISIBLE);
                findViewById(android.R.id.content).findViewById((R.id.left_drawer)).findViewById(R.id.hide_view3).setVisibility(View.INVISIBLE);
                findViewById(android.R.id.content).findViewById((R.id.left_drawer)).findViewById(R.id.hide_view4).setVisibility(View.INVISIBLE);
            }

            if (!np.isSelected()) {
                np.setMarqueeRepeatLimit(-1);
                np.setEllipsize(TextUtils.TruncateAt.MARQUEE);
                np.setHorizontallyScrolling(true);
                np.setMaxLines(1);
                np.setSelected(true);
            }
            //pb.setProgress(song_length_position);

            ls.setText("Listeners: " + listeners);


            if(!dj_name.getText().toString().equals(djname))
                dj_name.setText(djname);
            //dj_name.setTextColor(djhex);

            if(!nextsong.getText().toString().equals(ns)) {
                if (current_ui_json.getBoolean("isafkstream")) {
                    nextsong.setText(ns);
                } else {
                    nextsong.setText("No Queue");
                    nextsong.setTextColor(ResourcesCompat.getColor(getResources(), R.color.dark, null));
                }
            }

            // Fix for syncing play/pause button by taking advantage of the fact that this code gets
            // called after the JSON gets scraped everything the main activity is instantiated.
            // I don't know where else it could/should go.
            ImageButton img = (ImageButton)now_playing.findViewById(R.id.play_pause);
            if(PlayerState.CURRENTLY_PLAYING){
                img.setImageResource(R.drawable.pause_small);
            } else {
                img.setImageResource(R.drawable.arrow_small);
            }

            // I still do no know where things get instantiated so we'll do it here until we figure
            // out one day.
            np.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    setClipboard(v);
                    Toast.makeText(getApplicationContext(), "Copied Now Playing to the Clipboard", Toast.LENGTH_SHORT).show();
                    return true;
                }
            });
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void scrapeJSON(String urlToScrape){
        jsonTask.cancel(false);
        jsonTask = new JSONScraperTask(this, 0);
        jsonTask.execute(urlToScrape);
    }

    public void scrapeNews(String urlToScrape){
        new JSONScraperTask(this, 1).execute(urlToScrape);
    }

    public void setUIJSON(String jsonString) throws JSONException {
        current_ui_json = new JSONObject(new JSONObject(jsonString).getString("main"));
        updateUI();
    }

    public void clearFirstSearch(View v) {
        if(firstSearchClick) {
            EditText edts = (EditText) v;
            edts.getText().clear();
            firstSearchClick = false;

            edts.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override
                public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                    if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                        performSearch(1);
                        return true;
                    }
                    return false;
                }
            });
        }
    }

    public void searchButtonClick(View searchButton){
        performSearch(1);
    }

    public void clearSearchClick(View clearSearchButton) {
        View requestView = viewPager.findViewById(R.id.requests_page);

        EditText queryEditor = (EditText) requestView.findViewById(R.id.searchquery);
        queryEditor.setText("");

        ListView songListView = (ListView) requestView.findViewById(R.id.songListView);
        ArrayList<Song> emptyList = new ArrayList<>();
        songListView.removeFooterView(searchFooter);
        songListView.setAdapter(new SongAdapter(this, R.layout.request_cell, emptyList));
    }

    private void performSearch(Integer pageNumber){
        View curView = getCurrentFocus();
        View requestView = viewPager.findViewById(R.id.requests_page);
        EditText queryEditor = (EditText) requestView.findViewById(R.id.searchquery);
        TextView searchMsg = (TextView) requestView.findViewById(R.id.searchMsg);
        String query = queryEditor.getText().toString().trim();
        searchMsg.setVisibility(View.VISIBLE);
        if(query.equals("")) {
            searchMsg.setText("You cannot search for nothing.");
        } else {
            searchMsg.setText("Searching...");
            if (curView != null) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(curView.getWindowToken(), 0);
            }

            String searchURL = String.format(SEARCH_API, query, pageNumber);
            new JSONScraperTask(this, 2).execute(searchURL);
        }
    }

    public void setSongList(String json) throws JSONException{
        View requestView = viewPager.findViewById(R.id.requests_page);
        TextView searchMsg = (TextView) requestView.findViewById(R.id.searchMsg);
        ListView songListView = (ListView) requestView.findViewById(R.id.songListView);
        Integer curPage, lastPage;

        try {
            JSONObject searchObject = new JSONObject(json);
            JSONArray songs = new JSONArray(searchObject.getString("data"));
            ArrayList<Song> songList = new ArrayList<>();
            curPage = searchObject.getInt("current_page");
            lastPage = searchObject.getInt("last_page");

            for (int i = 0; i < songs.length(); i++){
                JSONObject songObject = songs.getJSONObject(i);

                if(songObject != null){
                    String artist = songObject.getString("artist");
                    String title = songObject.getString("title");
                    Integer songID = songObject.getInt("id");
                    boolean requestable = songObject.getBoolean("requestable");
                    Song song = new Song(artist, title, songID, requestable);

                    songList.add(song);
                }
            }

            if(songs.length() == 0) {
                searchMsg.setVisibility(View.VISIBLE);
                searchMsg.setText("No songs found for query.");
            } else {
                searchMsg.setVisibility(View.INVISIBLE);
            }

            if(searchFooter == null || songListView.getFooterViewsCount() == 0) {
                createSearchFooter(curPage, lastPage);
                songListView.addFooterView(searchFooter);
            }
            else {
                createSearchFooter(curPage, lastPage);
            }
            SongAdapter sAdapt = new SongAdapter(this, R.layout.request_cell, songList);
            songListView.setAdapter(sAdapt);
        }
        catch(JSONException ex){
            searchMsg.setVisibility(View.VISIBLE);
            searchMsg.setText("An error occurred while retrieving songs. Please try again.");
        }
    }

    private void createSearchFooter(final Integer curPage, final Integer lastPage) {
        View view = searchFooter;

        if(view == null)
            view = this.getLayoutInflater().inflate(R.layout.page_buttons, null);

        if(curPage <= 5) {
            Button searchPageButton = (Button) view.findViewById(R.id.searchB1);

            searchPageButton.setTextColor(getResources().getColor(R.color.rblue));
            searchPageButton.setEnabled(true);
            searchPageButton.setVisibility(View.VISIBLE);
        }
        else {
            Button searchPageButton = (Button) view.findViewById(R.id.searchB1);

            searchPageButton.setText(R.string.dots);
            searchPageButton.setTextColor(getResources().getColor(R.color.whited2));
            searchPageButton.setEnabled(false);
        }

        if(curPage <= 4) {
            view.findViewById(R.id.searchFirstPage).setVisibility(View.INVISIBLE);
        }
        else {
            Button searchPageButton = (Button) view.findViewById(R.id.searchFirstPage);

            searchPageButton.setVisibility(View.VISIBLE);
            searchPageButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    performSearch(1);
                }
            });
        }

        if(curPage <= 3) {
            view.findViewById(R.id.searchB1).setVisibility(View.INVISIBLE);
        }
        else {
            Button searchPageButton = (Button) view.findViewById(R.id.searchB1);
            Integer num = curPage - 3;

            searchPageButton.setVisibility(View.VISIBLE);

            if(curPage <= 5) {
                searchPageButton.setText(num.toString());
                searchPageButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        performSearch(curPage - 3);
                    }
                });
            }
        }

        if(curPage <= 2) {
            view.findViewById(R.id.searchB2).setVisibility(View.INVISIBLE);
        }
        else {
            Button searchPageButton = (Button) view.findViewById(R.id.searchB2);
            Integer num = curPage - 2;

            searchPageButton.setVisibility(View.VISIBLE);
            searchPageButton.setText(num.toString());
            searchPageButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    performSearch(curPage - 2);
                }
            });
        }

        if(curPage == 1) {
            view.findViewById(R.id.searchB3).setVisibility(View.INVISIBLE);
        }
        else {
            Button searchPageButton = (Button) view.findViewById(R.id.searchB3);
            Integer num = curPage - 1;

            searchPageButton.setVisibility(View.VISIBLE);
            searchPageButton.setText(num.toString());
            searchPageButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    performSearch(curPage - 1);
                }
            });
        }

        if(curPage >= (lastPage - 4)) {
            Button searchPageButton = (Button) view.findViewById(R.id.searchB7);

            searchPageButton.setTextColor(getResources().getColor(R.color.rblue));
            searchPageButton.setEnabled(true);
            searchPageButton.setVisibility(View.VISIBLE);
        }
        else {
            Button searchPageButton = (Button) view.findViewById(R.id.searchB7);

            searchPageButton.setText(R.string.dots);
            searchPageButton.setTextColor(getResources().getColor(R.color.whited2));
            searchPageButton.setEnabled(false);
        }

        if(curPage >= (lastPage - 3)) {
            view.findViewById(R.id.searchLastPage).setVisibility(View.INVISIBLE);
        }
        else {
            Button searchPageButton = (Button) view.findViewById(R.id.searchLastPage);

            searchPageButton.setVisibility(View.VISIBLE);
            searchPageButton.setText(lastPage.toString());
            searchPageButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    performSearch(lastPage);
                }
            });
        }

        if(curPage >= (lastPage - 2)) {
            view.findViewById(R.id.searchB7).setVisibility(View.INVISIBLE);
        }
        else {
            Button searchPageButton = (Button) view.findViewById(R.id.searchB7);
            Integer num = curPage + 3;

            searchPageButton.setVisibility(View.VISIBLE);

            if(curPage >= (lastPage - 4)) {
                searchPageButton.setText(num.toString());
                searchPageButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        performSearch(curPage + 3);
                    }
                });
            }
        }

        if(curPage >= (lastPage - 1)) {
            view.findViewById(R.id.searchB6).setVisibility(View.INVISIBLE);
        }
        else {
            Button searchPageButton = (Button) view.findViewById(R.id.searchB6);
            Integer num = curPage + 2;

            searchPageButton.setVisibility(View.VISIBLE);
            searchPageButton.setText(num.toString());
            searchPageButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    performSearch(curPage + 2);
                }
            });
        }

        if(curPage.equals(lastPage)) {
            view.findViewById(R.id.searchB5).setVisibility(View.INVISIBLE);
        }
        else {
            Button searchPageButton = (Button) view.findViewById(R.id.searchB5);
            Integer num = curPage + 1;

            searchPageButton.setVisibility(View.VISIBLE);
            searchPageButton.setText(num.toString());
            searchPageButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    performSearch(curPage + 1);
                }
            });
        }

        ((Button) view.findViewById(R.id.searchB4)).setText(curPage.toString());
        searchFooter = view;
    }

    public void setNewsUI(String jsonString) throws JSONException {
        JSONArray newsjson = new JSONArray(jsonString);
        View news_view = viewPager.getChildAt(2);

        if(news_view != null) {
            TextView newst1 = (TextView) news_view.findViewById(R.id.news_title1);
            TextView newst2 = (TextView) news_view.findViewById(R.id.news_title2);
            TextView newst3 = (TextView) news_view.findViewById(R.id.news_title3);
            newst1.setText(newsjson.getJSONObject(0).getString("title"));
            newst2.setText(newsjson.getJSONObject(1).getString("title"));
            newst3.setText(newsjson.getJSONObject(2).getString("title"));
            TextView news1 = (TextView) news_view.findViewById(R.id.news1);
            TextView news2 = (TextView) news_view.findViewById(R.id.news2);
            TextView news3 = (TextView) news_view.findViewById(R.id.news3);
            news1.setText(Html.fromHtml((newsjson.getJSONObject(0).getString("text"))));
            news1.setMovementMethod(LinkMovementMethod.getInstance());
            news2.setText(Html.fromHtml((newsjson.getJSONObject(1).getString("text"))));
            news2.setMovementMethod(LinkMovementMethod.getInstance());
            news3.setText(Html.fromHtml((newsjson.getJSONObject(2).getString("text"))));
            news3.setMovementMethod(LinkMovementMethod.getInstance());
            newsSet = true;
        }
    }

    public void scrapeDJImage(String urlToScrape){
        djimageTask.cancel(false);
        djimageTask = new DJImageTask(this);
        djimageTask.execute(urlToScrape);
    }

    public void setDJImage(RoundedBitmapDrawable djimage) {
        ImageView djavatar = (ImageView)viewPager.getChildAt(0).findViewById(R.id.dj_avatar);
        djavatar.setImageDrawable(djimage);
    }

    public void makeRequest(Integer songID){
        mRequestor.Request(songID);
    }

    private void updateSongProgress(HashMap<String, Integer> values)
    {
        View now_playing = viewPager.getChildAt(0);

        if(now_playing != null) {
            ProgressBar pb = (ProgressBar) now_playing.findViewById(R.id.progressBar3);
            TextView te = (TextView) now_playing.findViewById(R.id.time_elapsed);
            TextView tt = (TextView) now_playing.findViewById(R.id.total_time);

            if (values.containsKey("length")) {
                pb.setProgress(0);
                pb.setMax(values.get("length"));
            }

            if (values.containsKey("position")) {
                Integer position = values.get("position");

                if (position <= pb.getMax())
                    pb.setProgress(position);
            }

            if (values.containsKey("totalMinutes") && values.containsKey("totalSeconds")) {
                Integer minutes = values.get("totalMinutes");
                Integer seconds = values.get("totalSeconds");
                tt.setText(minutes.toString() + ":" + String.format("%02d", seconds));
            }

            if (values.containsKey("elapsedMinutes") && values.containsKey("elapsedSeconds")) {
                Integer minutes = values.get("elapsedMinutes");
                Integer seconds = values.get("elapsedSeconds");
                te.setText(minutes.toString() + ":" + String.format("%02d", seconds));
            }
        }
    }

    private void calculateSongTimes()
    {
        try{
            final HashMap<String, Integer> songVals = new HashMap<>();
            Integer start, end, position;

            synchronized (lock) {
                if(songTimes.containsKey("start"))
                    start = songTimes.get("start");
                else
                    start = 0;

                if(songTimes.containsKey("end"))
                    end = songTimes.get("end");
                else
                    end = 0;

                if(songTimes.containsKey("position"))
                    position = songTimes.get("position");
                else
                    position = 0;

                if(songChanged){
                    songChanged = false;

                    if(PlayerState.CURRENTLY_PLAYING)
                        sendBluetoothMeta = true;

                    Integer length = end - start;
                    Integer totalMinutes = length / 60;
                    Integer totalSeconds = length % 60;

                    songTimes.put("length", length);
                    songTimes.put("totalMinutes", totalMinutes);
                    songTimes.put("totalSeconds", totalSeconds);

                    songVals.put("length", length * 1000);
                    songVals.put("totalMinutes", totalMinutes);
                    songVals.put("totalSeconds", totalSeconds);
                }
                else{
                    position += UPDATE_SONGTIME_INTERVAL;
                    songTimes.put("position", position);
                }
            }

            songVals.put("position", position);
            songVals.put("elapsedMinutes", (position / 1000) / 60);
            songVals.put("elapsedSeconds", (position / 1000) % 60);

            // Bluetooth Tag?
            if(sendBluetoothMeta){
                sendBluetoothMeta = false;
                maybeUpdateBluetooth();
            }

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateSongProgress(songVals);
                }
            });
        }
        catch(Exception ex) {}
    }

    private boolean isDrawerVisible(View view) {

        Rect scrollBounds = new Rect();
        view.getHitRect(scrollBounds);
        if (view.findViewById(R.id.drawer_layout).findViewById(R.id.left_drawer).getLocalVisibleRect(scrollBounds)) {
            return true;
        } else {
            return false;
        }
    }

    public void openSideDrawer(View v) {
        DrawerLayout dl = (DrawerLayout)findViewById(android.R.id.content).findViewById(R.id.drawer_layout);
        dl.openDrawer(dl.findViewById(R.id.left_drawer));
    }

    public void closeSideDrawer() {
        DrawerLayout dl = (DrawerLayout)findViewById(android.R.id.content).findViewById(R.id.drawer_layout);
        dl.closeDrawer(dl.findViewById(R.id.left_drawer));
    }

    private void playPlayerService() {
        Intent i = new Intent(this, RadioService.class);
        i.putExtra("action", "io.r_a_d.radio.PLAY");
        startService(i);
    }

    private void pausePlayerService() {
        Intent i = new Intent(this, RadioService.class);
        i.putExtra("action", "io.r_a_d.radio.PAUSE");
        startService(i);
    }

    private void setClipboard(View v) {
        String text = ((TextView) v).getText().toString();
        if(android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.HONEYCOMB) {
            android.text.ClipboardManager clipboard = (android.text.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setText(text);
        } else {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("Copied Text", text);
            clipboard.setPrimaryClip(clip);
        }
    }

    public void togglePlayPause(View v) {
        if(isDrawerVisible(findViewById(android.R.id.content))) return;
        ImageButton img = (ImageButton)v.findViewById(R.id.play_pause);
        if(!PlayerState.CURRENTLY_PLAYING){
            img.setImageResource(R.drawable.pause_small);
            playPlayerService();
            sendBluetoothMeta = true;
        } else {
            img.setImageResource(R.drawable.arrow_small);
            pausePlayerService();
            sendBluetoothMeta = false;
        }
    }

    private void initSessionManagerListener() {
        mSessionManagerListener = new SessionManagerListener<CastSession>() {

            @Override
            public void onSessionEnded(CastSession session, int error) {
                onApplicationDisconnected();
            }

            @Override
            public void onSessionResumed(CastSession session, boolean wasSuspended) {
                onApplicationConnected(session);
            }

            @Override
            public void onSessionResumeFailed(CastSession session, int error) {
                onApplicationDisconnected();
            }

            @Override
            public void onSessionStarted(CastSession session, String sessionId) {
                onApplicationConnected(session);
            }

            @Override
            public void onSessionStartFailed(CastSession session, int error) {
                onApplicationDisconnected();
            }

            @Override
            public void onSessionStarting(CastSession session) {
            }

            @Override
            public void onSessionEnding(CastSession session) {
            }

            @Override
            public void onSessionResuming(CastSession session, String sessionId) {
            }

            @Override
            public void onSessionSuspended(CastSession session, int reason) {
            }

            private void onApplicationConnected(CastSession castSession) {
                mCastSession = castSession;
                RemoteMediaClient remoteMediaClient = mCastSession.getRemoteMediaClient();
                /* maybe add RemoteMedia.Listener */
                MediaInfo m = new MediaInfo.Builder("https://stream.r-a-d.io/main.mp3")
                        .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                        .setContentType("audio/mpeg")
                        .build();
                MediaLoadOptions mediaLoadOptions = new MediaLoadOptions.Builder()
                        .setAutoplay(true)
                        .build();
                remoteMediaClient.load(m, mediaLoadOptions);
                invalidateOptionsMenu();
            }

            private void onApplicationDisconnected() {
                invalidateOptionsMenu();
            }
        };
    }
}
