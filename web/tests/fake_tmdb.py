"""Låtsas-TMDB för testerna: samma form på svaren som api.themoviedb.org/3."""
from aiohttp import web

V4 = "eyJ" + "a" * 200  # som en läs-token (v4)


def movie(i, title, year="2026-03-01", backdrop=True, **extra):
    return {"id": i, "media_type": "movie", "title": title, "original_title": title, "release_date": year,
            "poster_path": f"/p{i}.jpg", "backdrop_path": f"/b{i}.jpg" if backdrop else None,
            "overview": f"Om {title}.", "vote_average": 7.84, "vote_count": 1200, "popularity": 100 - i % 50, "adult": False} | extra


def show(i, name, year="2025-09-01", **extra):
    return {"id": i, "media_type": "tv", "name": name, "original_name": name, "first_air_date": year,
            "poster_path": f"/p{i}.jpg", "backdrop_path": f"/b{i}.jpg", "overview": f"Om {name}.",
            "vote_average": 8.1, "vote_count": 400, "popularity": 50} | extra


def page(results, page=1, pages=3):
    return {"page": page, "results": results, "total_pages": pages, "total_results": pages * 20}


def make_app(log: list, image_dir: str | None = None) -> web.Application:
    app = web.Application()

    def auth(req):
        if req.query.get("api_key") != "nyckel" and req.headers.get("Authorization") != f"Bearer {V4}":
            raise web.HTTPUnauthorized(text='{"status_message": "Invalid API key"}')
        log.append((req.path, dict(req.query)))

    def strip(items, kind):
        return [{k: v for k, v in o.items() if k != "media_type"} for o in items]

    async def trending(req):
        auth(req)
        return web.json_response(page([
            movie(500, "Filmen som finns"), movie(601, "Ny film"), show(900, "Serien som finns"), show(950, "Fjällstationen"),
            {"id": 77, "media_type": "person", "name": "Lisa Ek"},
            movie(666, "Vuxenfilm", adult=True),
        ]))

    async def popular_movies(req):
        auth(req)
        p = int(req.query.get("page") or 1)
        return web.json_response(page(strip([movie(601, "Ny film"), movie(602, "Utan svensk text"), movie(500, "Filmen som finns")], "movie"), p))

    async def popular_tv(req):
        auth(req)
        return web.json_response(page(strip([show(950, "Fjällstationen"), show(900, "Serien som finns")], "tv")))

    async def discover_movie(req):
        auth(req)
        q = req.query
        p = int(q.get("page") or 1)
        if q.get("with_genres"):
            g = int(q["with_genres"])
            return web.json_response(page(strip([movie(700 + g % 50, f"Genrefilm {g}", backdrop=g != 35), movie(800 + g % 50, f"Genrefilm {g} b")], "movie"), p))
        if q.get("with_companies"):
            return web.json_response(page(strip([movie(610, f"Studiofilm {q['with_companies']}")], "movie"), p))
        if q.get("primary_release_date.gte"):
            return web.json_response(page(strip([movie(620, "Kommer snart", year="2027-01-01")], "movie"), p))
        return web.json_response(page([], p, 1))

    async def discover_tv(req):
        auth(req)
        q = req.query
        if q.get("with_genres"):
            return web.json_response(page(strip([show(960, f"Genreserie {q['with_genres']}")], "tv")))
        if q.get("with_networks"):
            return web.json_response(page(strip([show(970, f"Nätverksserie {q['with_networks']}")], "tv")))
        return web.json_response(page(strip([show(980, "Kommande serie", year="2027-02-02")], "tv")))

    async def genres(req):
        auth(req)
        if req.match_info["kind"] == "movie":
            return web.json_response({"genres": [{"id": 28, "name": "Action"}, {"id": 35, "name": "Komedi"}]})
        return web.json_response({"genres": [{"id": 10759, "name": "Action & Adventure"}]})

    async def org(req):
        auth(req)
        i = int(req.match_info["id"])
        return web.json_response({"id": i, "name": f"{req.match_info['kind'].title()} {i}", "logo_path": f"/logo{i}.png"})

    async def movie_details(req):
        auth(req)
        i = int(req.match_info["id"])
        en = req.query.get("language") == "en-US"
        base = movie(i, "Ny film" if i != 602 else "Utan svensk text")
        base.pop("media_type")
        if i == 602 and not en:
            base["overview"] = ""
        if i == 602 and en:
            base["overview"] = "English overview."
        if "append_to_response" not in req.query:
            return web.json_response(base)
        return web.json_response(base | {
            "runtime": 128, "tagline": "Allt förändras.", "status": "Released", "imdb_id": f"tt{i}",
            "genres": [{"id": 28, "name": "Action"}], "original_language": "en",
            "production_companies": [{"id": 2, "name": "Walt Disney Pictures"}],
            "credits": {"cast": [{"id": 77, "name": "Lisa Ek", "character": "Maja", "profile_path": "/lisa.jpg"},
                                 {"id": 78, "name": "Johan Berg", "character": "Nils", "profile_path": None}],
                        "crew": [{"id": 79, "name": "Anna Lund", "job": "Director"}, {"id": 80, "name": "X", "job": "Writer"}]},
            "videos": {"results": [
                {"site": "YouTube", "key": "teaserENxxx", "type": "Teaser", "official": True, "iso_639_1": "en"},
                {"site": "YouTube", "key": "trailerENxx", "type": "Trailer", "official": True, "iso_639_1": "en"},
                {"site": "YouTube", "key": "trailerSVxx", "type": "Trailer", "official": True, "iso_639_1": "sv"},
                {"site": "Vimeo", "key": "123", "type": "Trailer", "official": True, "iso_639_1": "sv"}]},
            "external_ids": {"imdb_id": f"tt{i}"},
            "release_dates": {"results": [{"iso_3166_1": "US", "release_dates": [{"certification": "PG-13"}]},
                                          {"iso_3166_1": "SE", "release_dates": [{"certification": ""}, {"certification": "15"}]}]},
            "watch/providers": {"results": {"SE": {"flatrate": [{"provider_id": 8, "provider_name": "Netflix", "logo_path": "/netflix.png", "display_priority": 1}],
                                                   "rent": [{"provider_id": 2, "provider_name": "Apple TV", "logo_path": "/apple.png", "display_priority": 2},
                                                            {"provider_id": 8, "provider_name": "Netflix", "logo_path": "/netflix.png", "display_priority": 1}]}}},
            "recommendations": {"results": [movie(500, "Filmen som finns"), movie(603, "Rekommenderad")]},
            "similar": {"results": [movie(604, "Liknande")]},
        })

    async def tv_details(req):
        auth(req)
        i = int(req.match_info["id"])
        base = show(i, "Fjällstationen")
        base.pop("media_type")
        if "append_to_response" not in req.query:
            return web.json_response(base)
        return web.json_response(base | {
            "episode_run_time": [52], "number_of_seasons": 2, "number_of_episodes": 16, "status": "Returning Series",
            "genres": [{"id": 18, "name": "Drama"}], "created_by": [{"name": "Sara Nyberg"}], "networks": [{"id": 87, "name": "SVT1"}],
            "credits": {"cast": [], "crew": []}, "videos": {"results": []},
            "external_ids": {"tvdb_id": 373737, "imdb_id": "tt9500"},
            "content_ratings": {"results": [{"iso_3166_1": "SE", "rating": "11"}]},
            "watch/providers": {"results": {}}, "recommendations": {"results": [show(900, "Serien som finns")]}, "similar": {"results": []},
        })

    async def external(req):
        auth(req)
        i = int(req.match_info["id"])
        if req.match_info["kind"] == "tv":
            return web.json_response({"id": i, "tvdb_id": 373737 if i == 950 else 1000 + i, "imdb_id": f"tt9{i}"})
        return web.json_response({"id": i, "imdb_id": f"tt{i}"})

    async def person(req):
        auth(req)
        return web.json_response({
            "id": 77, "name": "Lisa Ek", "profile_path": "/lisa.jpg", "known_for_department": "Acting", "birthday": "1980-02-03",
            "place_of_birth": "Umeå", "biography": "Skådespelare från Umeå.",
            "combined_credits": {"cast": [movie(601, "Ny film", vote_count=30, popularity=10), show(950, "Fjällstationen"), movie(500, "Filmen som finns", vote_count=9000)],
                                 "crew": [movie(601, "Ny film")]},
        })

    async def search(req):
        auth(req)
        return web.json_response(page([movie(601, "Ny film"), show(950, "Fjällstationen"),
                                       {"id": 77, "media_type": "person", "name": "Lisa Ek", "profile_path": "/lisa.jpg", "known_for_department": "Acting"}], 1, 1))

    async def image(req):
        log.append(("img", req.path))
        if image_dir:
            # Riktiga bilder (för webbläsartestet): affisch, bakgrund eller logotyp.
            f = req.match_info["file"]
            name = "logo.png" if f.startswith("logo") or f.endswith(".png") else "backdrop.jpg" if req.match_info["size"] in ("w780", "w1280") else "poster.jpg"
            return web.FileResponse(f"{image_dir}/{name}")
        return web.Response(body=b"\xff\xd8\xff\xe0bild", content_type="image/jpeg")

    app.router.add_get("/3/trending/all/{window}", trending)
    app.router.add_get("/3/movie/popular", popular_movies)
    app.router.add_get("/3/tv/popular", popular_tv)
    app.router.add_get("/3/discover/movie", discover_movie)
    app.router.add_get("/3/discover/tv", discover_tv)
    app.router.add_get("/3/genre/{kind}/list", genres)
    app.router.add_get("/3/{kind:company|network}/{id}", org)
    app.router.add_get("/3/movie/{id:[0-9]+}", movie_details)
    app.router.add_get("/3/tv/{id:[0-9]+}", tv_details)
    app.router.add_get("/3/{kind:movie|tv}/{id:[0-9]+}/external_ids", external)
    app.router.add_get("/3/person/{id}", person)
    app.router.add_get("/3/search/multi", search)
    app.router.add_get("/t/p/{size}/{file}", image)
    return app
