"""Dispatch authenticated JSON requests; transport stays in api.Handler."""

from . import files, media, projects, system, threads, todos
from .common import ApiError


_ROUTES = {
    "node": system.route,
    "goals": system.route,
    "models": system.route,
    "usage": system.route,
    "activity": system.route,
    "requests": system.route,
    "submissions": system.route,
    "media": media.route,
    "speech": media.route,
    "download-links": files.route,
    "files": files.route,
    "directories": files.route,
    "project-directories": projects.route,
    "projects": projects.route,
    "threads": threads.route,
    "todos": todos.route,
}


def dispatch(handler, path, query, body, connection):
    route = _ROUTES.get(path[1]) if len(path) >= 2 and path[0] == "v1" else None
    if route is None:
        raise ApiError(404, "not_found", "Unknown endpoint")
    return route(handler, path, query, body, connection)
