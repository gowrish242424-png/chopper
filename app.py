from flask import Flask, request, jsonify
from tools.web_tool import search_web

app = Flask(__name__)


@app.route("/")
def home():
    return jsonify({
        "status": "Chopper server online"
    })


@app.route("/health")
def health():
    return jsonify({
        "status": "Chopper web server online"
    })


@app.route("/search", methods=["POST"])
def search():
    data = request.get_json(silent=True) or {}
    query = str(data.get("query", "")).strip()

    if not query:
        return jsonify({
            "success": False,
            "error": "No query provided"
        }), 400

    try:
        result = search_web(query, max_results=5)

        return jsonify({
            "success": True,
            "query": query,
            "result": result
        })

    except Exception as error:
        return jsonify({
            "success": False,
            "error": str(error)
        }), 500
print("===== CHOPPER RENDER APP LOADED =====")
print(app.url_map)