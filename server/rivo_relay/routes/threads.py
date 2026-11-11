"""Authenticated JSON routes for threads resources."""

from ..client_projection import client_history, client_session
from .common import ApiError, required, permission_options, native_input


def route(handler, path, query, body, connection):
    method = handler.command
    codex = handler.app.codex
    connection_id = connection["connection_id"]
    if path == ["v1", "threads"]:
        if method == "GET":
            project = query.get("projectId", [""])[0]
            if not project:
                raise ApiError(400, "invalid_request", "projectId is required")
            archived = query.get('archived', ['false'])[0]
            if archived not in ('true', 'false'):
                raise ApiError(400, 'invalid_request', 'archived must be true or false')
            view = query.get('view', ['full'])[0]
            if view not in ('full', 'summary'):
                raise ApiError(400, 'invalid_request', 'Unknown thread view')
            if view == 'summary':
                return {"threads": codex.list_sessions(project, archived=archived == 'true', summary=True),
                        "catalogComplete": False}
            return {"threads": codex.list_sessions(project, archived=True) if archived == 'true' else codex.list_sessions(project),
                    "catalogComplete": True}
        if method == "POST":
            project, sid = required(body, "projectId"), required(body, "submissionId")
            options = permission_options(body)
            if body.get('worktreeRef'):
                options['worktree_ref'] = body['worktreeRef']
            return handler.app.submit(connection_id, sid, "thread.create", project, body,
                lambda: codex.create_session(project, **options))
    if len(path) >= 3 and path[:2] == ["v1", "threads"]:
        if len(path) == 4 and path[3] == 'prediction' and method == 'POST':
            return codex.predict_message(path[2], required(body, 'expectedTurnId'))
        if len(path) == 4 and path[3] == "ownership" and method == "GET":
            return codex.preview_handoff(path[2], query.get("operation", ["takeover"])[0], connection_id)
        thread_id = path[2]
        if len(path) == 4 and path[3] == 'fork' and method == 'POST':
            sid, turn_id = required(body, 'submissionId'), required(body, 'lastTurnId')
            ref = required(body, 'worktreeRef') if 'worktreeRef' in body else None
            return handler.app.submit(connection_id, sid, 'thread.fork', thread_id, body,
                lambda: codex.fork_session(thread_id, turn_id, worktree_ref=ref))
        if len(path) == 4 and path[3] == 'fork-context' and method == 'GET':
            codex.start()
            source = codex.connection.request('thread/read', {'threadId': thread_id, 'includeTurns': False})['thread']
            from ..codex.developer import project_git
            return {'projectId': source.get('projectId'), 'cwd': source.get('cwd'),
                    'git': project_git(codex.connection, source['cwd'])}
        if len(path) == 3 and method == "GET":
            options = {'native_revision': query['nativeRevision'][0]} if query.get('nativeRevision') else {}
            limit = int(query.get("limit", ["8"])[0])
            cursor = query.get("cursor", [None])[0]
            result = codex.get_session(thread_id, cursor=cursor, limit=limit, **options)
            result["queue"] = handler.app.queue.list(thread_id)
            compact_images = handler.observe_history_media(result.get('thread'))
            return client_session(result, known_head_revision=query.get("knownHeadRevision", [None])[0],
                                  limit=limit, cursor=cursor, compact_images=compact_images)
        if len(path) == 4 and path[3] == "history" and method == "GET":
            options = {'native_revision': query['nativeRevision'][0]} if query.get('nativeRevision') else {}
            result = codex.history(thread_id, cursor=query.get("cursor", [None])[0],
                                   limit=int(query.get("limit", ["30"])[0]), **options)
            compact_images = handler.observe_history_media({'id': thread_id, **result})
            projected = client_history({'threadId': thread_id, **result}, compact_images=compact_images)
            if 'threadId' not in result:
                projected.pop('threadId', None)
            return projected
        if len(path) == 4 and method == 'GET':
            if path[3] == 'diff':
                from ..codex.diff_projection import diff_page
                offset = int(query.get('offset', ['0'])[0])
                if offset > 0 and not query.get('revision'):
                    raise ApiError(400, 'invalid_request', 'revision required for pagination')
                return {'diff': diff_page(codex.diff(thread_id), revision=query.get('revision', [None])[0],
                    file_id=query.get('fileId', [None])[0], offset=offset,
                    limit=int(query.get('limit', ['400'])[0]))}
            if path[3] == 'review-context':
                return codex.review_context(thread_id)
            if path[3] == 'workspace':
                codex.start()
                return {'workspace': codex.workspaces.inspect(thread_id) if codex.workspaces else None,
                        'externalWorkspace': codex.external_workspace(thread_id)}
        if len(path) == 5 and path[3:] == ['workspace', 'cleanup'] and method == 'POST':
            sid, revision = required(body, 'submissionId'), required(body, 'revision')
            discard = body.get('discard', False)
            if not isinstance(discard, bool):
                raise ApiError(400, 'invalid_request', 'discard must be boolean')
            if handler.app.queue.list(thread_id):
                raise ApiError(409, 'workspace_busy', '请先处理此会话的执行队列')
            return handler.app.submit(connection_id, sid, 'workspace.cleanup', thread_id, body,
                lambda: codex.workspaces.cleanup(thread_id, revision, discard))
        if len(path) == 4 and path[3] == "queue" and method == "POST":
            sid, operation = required(body, "submissionId"), required(body, "operation")
            if operation == "enqueue":
                items = native_input(body)
                if not items: raise ApiError(400, "invalid_request", "input required")
                for key in ("model", "effort"):
                    if body.get(key) is not None and (not isinstance(body[key], str) or len(body[key]) > 200):
                        raise ApiError(400, "invalid_request", "Invalid model settings")
                if body.get('mode') not in (None, 'default', 'plan'):
                    raise ApiError(400, 'invalid_request', 'Invalid mode')
                if body.get('serviceTier') not in (None, 'default', 'priority'):
                    raise ApiError(400, 'invalid_request', 'Invalid serviceTier')
                fn = lambda: handler.app.queue.enqueue(thread_id, sid, connection_id, {"input": items, "model": body.get("model"), "effort": body.get("effort"), 'mode': body.get('mode'), 'serviceTier': body.get('serviceTier')})
            elif operation == "reorder":
                ids = body.get("ids")
                if not isinstance(ids, list) or not all(isinstance(i, str) for i in ids):
                    raise ApiError(400, "invalid_request", "ids required")
                fn = lambda: handler.app.queue.reorder(thread_id, ids, body.get("expectedIds"))
            elif operation in {"edit", "remove", "steer", "recover", "dismiss"}:
                item_id = required(body, "itemId")
                revision = body.get("revision")
                if not isinstance(revision, int) or isinstance(revision, bool):
                    raise ApiError(400, "invalid_request", "revision required")
                if operation == "steer":
                    turn_id = required(body, "expectedTurnId")
                    fn = lambda: handler.app.queue.steer(thread_id, item_id, revision, turn_id)
                elif operation == 'recover':
                    fn = lambda: handler.app.queue.recover(thread_id, item_id, revision, sid, connection_id)
                else:
                    fn = lambda: handler.app.queue.change(thread_id, item_id, revision, operation, body.get("text"))
            else:
                raise ApiError(400, "invalid_request", "Unknown queue operation")
            return handler.app.submit(connection_id, sid, "thread.queue." + operation, thread_id, body, fn)
        if len(path) == 4 and method == "POST":
            action, sid = path[3], required(body, "submissionId")
            if action == "pin":
                if not isinstance(body.get("pinned"), bool):
                    raise ApiError(400, "invalid_request", "pinned must be boolean")
                fn = lambda: codex.pin_session(thread_id, body["pinned"])
            elif action == 'goal':
                operation = body.get('operation')
                if operation not in ('set', 'pause', 'resume', 'clear'):
                    raise ApiError(400, 'invalid_request', 'Invalid goal operation')
                objective = required(body, 'objective', 200000) if operation == 'set' else None
                budget = body.get('tokenBudget')
                if budget is not None and (type(budget) is not int or not 0 < budget <= 9223372036854775807):
                    raise ApiError(400, 'invalid_request', 'tokenBudget must be a positive integer')
                fn = lambda: codex.update_goal(thread_id, operation, objective=objective, token_budget=budget)
            elif action == 'settings':
                if not any(body.get(key) is not None for key in ('model', 'effort', 'serviceTier', 'sandbox', 'approvalPolicy')):
                    raise ApiError(400, 'invalid_request', 'Settings required')
                for key in ('model', 'effort'):
                    if body.get(key) is not None:
                        required(body, key, 200)
                if body.get('serviceTier') not in (None, 'default', 'priority'):
                    raise ApiError(400, 'invalid_request', 'Invalid serviceTier')
                options = permission_options(body)
                fn = lambda: codex.update_settings(thread_id, model=body.get('model'), effort=body.get('effort'), service_tier=body.get('serviceTier'), **options)
            elif action == 'delete':
                fn = lambda: codex.delete_session(thread_id)
            elif action == 'archive':
                if not isinstance(body.get('archived'), bool):
                    raise ApiError(400, 'invalid_request', 'archived must be boolean')
                if body['archived'] and handler.app.queue.list(thread_id):
                    raise ApiError(409, 'thread_busy', '请先处理此会话的执行队列')
                fn = lambda: codex.archive_session(thread_id, body['archived'])
            elif action == 'review':
                from ..codex.developer import validate_review
                target = validate_review(body.get('target'))
                fn = lambda: handler.app.queue.enqueue(thread_id, sid, connection_id,
                    {'input': [{'type': 'text', 'text': '代码审查 · ' + target['type']}], 'review': target})
            elif action == "continue":
                resume_turn_id = required(body, 'expectedTurnId')
                fn = lambda: codex.send(thread_id, '', input_items=[], client_message_id=sid,
                    acquire_released=True, resume_turn_id=resume_turn_id)
            elif action == "turns":
                items = native_input(body)
                text = required(body, "text", 200000) if items is None else body.get("text", "")
                for key in ("model", "effort"):
                    if key in body and body[key] is not None and (not isinstance(body[key], str) or len(body[key]) > 200):
                        raise ApiError(400, "invalid_request", f"Invalid {key}")
                kwargs = {"model": body.get("model"), "effort": body.get("effort"), "client_message_id": sid}
                if body.get('mode') not in (None, 'default', 'plan'):
                    raise ApiError(400, 'invalid_request', 'Invalid mode')
                if body.get('mode') is not None:
                    kwargs['mode'] = body['mode']
                if body.get('serviceTier') not in (None, 'default', 'priority'):
                    raise ApiError(400, 'invalid_request', 'Invalid serviceTier')
                if body.get('serviceTier') is not None:
                    kwargs['service_tier'] = body['serviceTier']
                if items is not None:
                    kwargs["input_items"] = items
                if 'requireGoalSupport' in body:
                    if type(body['requireGoalSupport']) is not bool:
                        raise ApiError(400, 'invalid_request', 'requireGoalSupport must be boolean')
                    kwargs['require_goal_support'] = body['requireGoalSupport']
                fn = lambda: codex.send(thread_id, text, acquire_released=True, **kwargs)
            elif action == 'answers':
                turn_id = required(body, 'expectedTurnId')
                fn = lambda: codex.answer_questions(thread_id, turn_id, body.get('answers'), sid)
            elif action == "steer":
                items = native_input(body)
                text = required(body, "text", 200000) if items is None else body.get("text", "")
                turn_id = required(body, "expectedTurnId")
                kwargs = {"expected_turn_id": turn_id, "client_message_id": sid}
                if items is not None:
                    kwargs["input_items"] = items
                fn = lambda: codex.steer(thread_id, text, **kwargs)
            elif action == "ownership":
                operation = body.get("operation")
                if operation not in ("takeover", "release", "resume"):
                    raise ApiError(400, "invalid_request", "Invalid ownership operation")
                ticket = required(body, "ticket", 128)
                options = {'rebind_workspace': required(body, 'rebindWorkspace', 4096)} if 'rebindWorkspace' in body else {}
                def fn():
                    result = codex.handoff(thread_id, operation, request_id=handler._request_id, owner=connection_id, ticket=ticket, confirmed=body.get("confirmed") is True, **options)
                    # Submission receipts contain identities only; release
                    # metadata belongs to the preview/control projection.
                    return {"threadId": result["threadId"]}
            elif action == "interrupt":
                turn_id = required(body, "turnId")
                fn = lambda: codex.interrupt(thread_id, turn_id=turn_id)
            else:
                raise ApiError(404, "not_found", "Unknown operation")
            return handler.app.submit(connection_id, sid, f"thread.{action}", thread_id, body, fn)
    raise ApiError(404, "not_found", "Unknown endpoint")
