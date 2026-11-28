import { createServer } from 'node:http';
import { pathToFileURL } from 'node:url';

const image = (path, size) => typeof path === 'string' && /^\/[\w.-]+$/.test(path)
    ? 'https://image.tmdb.org/t/p/' + size + path : '';

function title(type, row) {
    const date = type === 'movie' ? row.release_date : row.first_air_date;
    return {
        url: 'https://www.themoviedb.org/' + type + '/' + row.id,
        title: row.title || row.name || row.original_title || row.original_name || '',
        originalTitle: row.original_title || row.original_name || '',
        kind: type === 'movie' ? 'Фильм' : 'Сериал',
        year: date?.slice(0, 4) || '',
        poster: image(row.poster_path, 'w500'),
        score: row.vote_count ? Number(row.vote_average).toFixed(1) : '',
    };
}

export function catalogServer({ token, fetcher = fetch, now = () => new Date() }) {
    const cache = new Map();
    async function tmdb(path, query) {
        const url = new URL('https://api.themoviedb.org/3' + path);
        url.search = new URLSearchParams({ language: 'ru-RU', ...query });
        const response = await fetcher(url, {
            headers: { Authorization: 'Bearer ' + token, Accept: 'application/json' },
            redirect: 'error', signal: AbortSignal.timeout(8000),
        });
        if (!response.ok) throw new Error('TMDB HTTP ' + response.status);
        return response.json();
    }
    async function recent() {
        const today = now().toISOString().slice(0, 10);
        const since = (Number(today.slice(0, 4)) - 1) + '-01-01';
        const discover = async (type, field) => {
            const filter = type === 'movie' ? 'primary_release_date' : field;
            const data = await tmdb('/discover/' + type, {
                [filter + '.gte']: since, [filter + '.lte']: today, include_adult: 'false', sort_by: 'popularity.desc',
            });
            return data.results.filter(row => Number.isInteger(row.id) && row[field] >= since && row[field] <= today)
                .map(row => title(type, row)).filter(item => item.title).slice(0, 10);
        };
        const [movies, shows] = await Promise.all([discover('movie', 'release_date'), discover('tv', 'first_air_date')]);
        return { items: movies.flatMap((movie, index) => [movie, shows[index]]).filter(Boolean).concat(shows.slice(movies.length)) };
    }
    async function detail(type, id) {
        const row = await tmdb('/' + type + '/' + id, {
            append_to_response: 'credits,videos',
            include_video_language: 'ru,en',
        });
        if (row.adult || String(row.id) !== id) return null;
        const videos = (row.videos?.results || []).filter(video => video.site === 'YouTube' &&
            video.type === 'Trailer' && /^[\w-]{11}$/.test(video.key));
        const trailer = videos.find(video => video.iso_639_1 === 'ru') || videos[0];
        return { item: title(type, row), description: row.overview || '', backdrop: image(row.backdrop_path, 'w1280'),
            genres: (row.genres || []).map(genre => genre.name).join(', '),
            cast: (row.credits?.cast || []).slice(0, 8).map(person => person.name),
            trailer: trailer ? 'https://www.youtube.com/watch?v=' + trailer.key : '' };
    }
    return createServer(async (req, res) => {
        const path = req.url;
        const match = /^\/v1\/title\/(movie|tv)\/([1-9]\d{0,8})$/.exec(path);
        if (req.method !== 'GET' || (path !== '/v1/new' && !match)) {
            res.writeHead(404).end();
            return;
        }
        let entry = cache.get(path);
        if (!entry || entry.expires < Date.now()) {
            if (cache.size >= 256) cache.delete(cache.keys().next().value);
            entry = { expires: Date.now() + 3_600_000, result: match ? detail(match[1], match[2]) : recent() };
            cache.set(path, entry);
        }
        try {
            const result = await entry.result;
            if (!result) { res.writeHead(404).end(); return; }
            res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'public, max-age=300' })
                .end(JSON.stringify(result));
        } catch {
            if (cache.get(path) === entry) cache.delete(path);
            res.writeHead(502).end();
        }
    });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    const token = process.env.TMDB_READ_TOKEN;
    if (!token) throw new Error('TMDB_READ_TOKEN is required');
    catalogServer({ token }).listen(Number(process.env.PORT || 8080), '0.0.0.0');
}
