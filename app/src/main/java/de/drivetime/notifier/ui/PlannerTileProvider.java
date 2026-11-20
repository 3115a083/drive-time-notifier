package de.drivetime.notifier.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import org.osmdroid.tileprovider.MapTileProviderBasic;
import org.osmdroid.tileprovider.MapTileRequestState;
import org.osmdroid.tileprovider.modules.IFilesystemCache;
import org.osmdroid.tileprovider.modules.CantContinueException;
import org.osmdroid.tileprovider.modules.INetworkAvailablityCheck;
import org.osmdroid.tileprovider.modules.MapTileDownloader;
import org.osmdroid.tileprovider.modules.TileDownloader;
import org.osmdroid.tileprovider.tilesource.ITileSource;

import de.drivetime.notifier.debug.RequestDebugLog;

/** Retains osmdroid's cache and backoff, and delivers a decoded download directly to the view. */
final class PlannerTileProvider extends MapTileProviderBasic {
    private DirectDownloader downloader;

    PlannerTileProvider(Context context, ITileSource source, TileDownloader transport) {
        super(context, source);
        if (transport != null) downloader.setTileDownloader(transport);
    }

    @Override protected MapTileDownloader createDownloaderProvider(INetworkAvailablityCheck network, ITileSource source) {
        downloader = new DirectDownloader(source, getTileWriter(), network);
        return downloader;
    }

    private static final class DirectDownloader extends MapTileDownloader {
        private final TileLoader loader = new DirectLoader();
        private Boolean lastSuccess;

        DirectDownloader(ITileSource source, IFilesystemCache cache, INetworkAvailablityCheck network) {
            super(source, cache, network);
        }

        @Override public TileLoader getTileLoader() { return loader; }

        private synchronized void record(boolean success, long elapsed) {
            if (lastSuccess != null && lastSuccess == success) return;
            lastSuccess = success;
            RequestDebugLog.INSTANCE.add("OpenStreetMap", "planner map tile", elapsed,
                    success ? "decoded" : "unavailable", "Visible map background");
        }

        private final class DirectLoader extends TileLoader {
            @Override public Drawable loadTile(long index) throws CantContinueException {
                long started = SystemClock.elapsedRealtime();
                Drawable tile = super.loadTile(index);
                record(tile != null, SystemClock.elapsedRealtime() - started);
                return tile;
            }

            @Override protected void tileLoaded(MapTileRequestState state, Drawable tile) {
                removeTileFromQueues(state.getMapTile());
                state.getCallback().mapTileRequestCompleted(state, tile);
            }
        }
    }
}
