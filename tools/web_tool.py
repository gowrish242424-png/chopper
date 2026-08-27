


from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone, timedelta
from email.utils import parsedate_to_datetime
from urllib.parse import urlparse, urlencode
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo
import json
import re

from ddgs import DDGS


TRUSTED_DOMAINS = {
    "reuters.com": 10,
    "apnews.com": 10,
    "bbc.com": 8,
    "bbc.co.uk": 8,
    "theguardian.com": 6,
    "nytimes.com": 6,
    "openai.com": 10,
    "anthropic.com": 10,
    "deepmind.google": 10,
    "ai.google": 10,
    "blog.google": 9,
    "microsoft.com": 9,
    "meta.com": 9,
    "nvidia.com": 9,
    "python.org": 10,
    "docs.python.org": 10,
    "peps.python.org": 10,
    "github.com": 7,
    "stackoverflow.com": 6,
    "realpython.com": 6,
    "nature.com": 10,
    "science.org": 10,
    "arxiv.org": 8,
    "india.gov.in": 10,
    "pib.gov.in": 10,
    "gov.in": 9,
    "tn.gov.in": 10,
    "lokbhavan.tn.gov.in": 10,
    "thehindu.com": 8,
    "indianexpress.com": 7,
    "economictimes.indiatimes.com": 6,
    "moneycontrol.com": 5,
    "timesofindia.indiatimes.com": 4,
}

AGGREGATOR_DOMAINS = {
    "yahoo.com": -30,
    "finance.yahoo.com": -35,
    "tech.yahoo.com": -30,
    "msn.com": -30,
    "aol.com": -30,
    "news.google.com": -40,
    "globenewswire.com": -50,
    "prnewswire.com": -50,
    "businesswire.com": -45,
    "einpresswire.com": -50,
    "247wallst.com": -25,
}

STOP_WORDS = {
    "the", "a", "an", "and", "or", "of", "to", "for", "in", "on",
    "with", "about", "is", "are", "what", "how", "latest", "news",
    "today", "recent", "current",
}


def tokenize(text):
    if not text:
        return set()

    words = re.findall(r"[a-zA-Z0-9+#.]+", text.lower())

    return {
        word
        for word in words
        if len(word) > 2 and word not in STOP_WORDS
    }


def core_topic_words(query):
    words = tokenize(query)
    expanded = set(words)
    lower = query.lower()

    if re.search(r"\bai\b", lower) or "artificial intelligence" in lower:
        expanded.update({
            "ai", "artificial", "intelligence", "openai", "anthropic",
            "deepmind", "chatgpt", "gemini", "llm",
        })

    return expanded


def normalize_domain(url):
    try:
        domain = urlparse(url).netloc.lower()
        if domain.startswith("www."):
            domain = domain[4:]
        return domain
    except Exception:
        return ""


def normalize_url(url):
    if not url:
        return ""
    url = url.split("#")[0]
    url = url.split("?")[0]
    return url.rstrip("/")


# =========================================================
# Query classification
# =========================================================


def is_time_query(query):
    q = query.lower()
    phrases = [
        "current time",
        "time right now",
        "time now",
        "what time is it",
        "what's the time",
        "what is the time",
    ]
    return any(phrase in q for phrase in phrases)


def is_date_query(query):
    q = query.lower()
    phrases = [
        "today's date",
        "todays date",
        "what is the date",
        "what's the date",
        "current date",
        "date today",
    ]
    return any(phrase in q for phrase in phrases)


def is_weather_query(query):
    q = query.lower()
    return any(word in q for word in ("weather", "temperature", "forecast"))


def is_news_query(query):
    q = query.lower()

    if is_time_query(q) or is_date_query(q) or is_weather_query(q):
        return False

    strong_news_markers = [
        "news",
        "breaking",
        "latest story",
        "latest stories",
        "latest developments",
        "recent developments",
        "headlines",
    ]

    if any(marker in q for marker in strong_news_markers):
        return True

    if any(word in q for word in ("latest", "today", "recent", "this week")):
        event_words = [
            "ai", "openai", "anthropic", "google", "microsoft", "meta",
            "technology", "tech", "politics", "election", "market",
            "sports", "war", "conflict", "announcement", "launch",
            "release", "update",
        ]
        return any(word in q for word in event_words)

    return False


def is_current_fact_query(query):
    q = query.lower().strip()

    if is_time_query(q) or is_date_query(q) or is_weather_query(q):
        return False

    current_markers = [
        "current",
        "currently",
        "present",
        "right now",
        "who is the",
        "who is",
    ]

    role_words = [
        "chief minister",
        "cm of",
        "prime minister",
        "president",
        "governor",
        "ceo",
        "chairman",
        "minister",
        "mayor",
        "captain",
    ]

    has_current_marker = any(
        marker in q
        for marker in current_markers
    )

    has_role = any(
        role in q
        for role in role_words
    )

    return has_current_marker and has_role

def requested_news_horizon(query):
    q = query.lower()

    if "last 24 hours" in q or "past 24 hours" in q:
        return timedelta(hours=24)

    if "today" in q:
        return timedelta(hours=24)

    if "last 3 days" in q or "past 3 days" in q:
        return timedelta(days=3)

    if "this week" in q or "last week" in q:
        return timedelta(days=7)

    if "this month" in q:
        return timedelta(days=31)

    if "latest" in q or "recent" in q or "news" in q:
        return timedelta(days=7)

    return None


# =========================================================
# Live utilities
# =========================================================


def _http_json(url, timeout=8):
    request = Request(
        url,
        headers={"User-Agent": "Mozilla/5.0 ChopperAI/1.0"},
    )

    with urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def _extract_location(query, fallback="India"):
    match = re.search(
        r"\b(?:in|at|for)\s+(.+?)(?:\?|$)",
        query.strip(),
        flags=re.IGNORECASE,
    )

    if match:
        location = match.group(1).strip(" .,!?:;")
        if location:
            return location

    if "india" in query.lower():
        return "India"

    return fallback


def _geocode_location(location):
    params = urlencode({
        "name": location,
        "count": 1,
        "language": "en",
        "format": "json",
    })

    data = _http_json(
        "https://geocoding-api.open-meteo.com/v1/search?" + params
    )

    results = data.get("results") or []

    if not results:
        return None

    item = results[0]

    return {
        "name": item.get("name") or location,
        "country": item.get("country") or "",
        "latitude": item.get("latitude"),
        "longitude": item.get("longitude"),
        "timezone": item.get("timezone"),
    }


def get_live_time_context(query):
    location = _extract_location(query)
    geo = _geocode_location(location)

    if not geo or not geo.get("timezone"):
        return ""

    try:
        now = datetime.now(ZoneInfo(geo["timezone"]))
    except Exception:
        return ""

    place = ", ".join(
        part
        for part in (geo.get("name", ""), geo.get("country", ""))
        if part
    )

    if is_date_query(query):
        return (
            "VERIFIED LIVE DATE\n"
            f"Location: {place}\n"
            f"Timezone: {geo['timezone']}\n"
            f"Today's date: {now.strftime('%A, %d %B %Y')}\n"
            "Instruction: Use this exact live date. Do not replace it with model memory."
        )

    current_time = now.strftime("%I:%M %p").lstrip("0")

    return (
        "VERIFIED LIVE TIME\n"
        f"Location: {place}\n"
        f"Timezone: {geo['timezone']}\n"
        f"Current time: {current_time}\n"
        f"Current date: {now.strftime('%A, %d %B %Y')}\n"
        "Instruction: Use this exact live time. Do not guess."
    )


def _weather_description(code):
    mapping = {
        0: "clear sky",
        1: "mainly clear",
        2: "partly cloudy",
        3: "overcast",
        45: "fog",
        48: "depositing rime fog",
        51: "light drizzle",
        53: "moderate drizzle",
        55: "dense drizzle",
        56: "light freezing drizzle",
        57: "dense freezing drizzle",
        61: "slight rain",
        63: "moderate rain",
        65: "heavy rain",
        66: "light freezing rain",
        67: "heavy freezing rain",
        71: "slight snow",
        73: "moderate snow",
        75: "heavy snow",
        77: "snow grains",
        80: "slight rain showers",
        81: "moderate rain showers",
        82: "violent rain showers",
        85: "slight snow showers",
        86: "heavy snow showers",
        95: "thunderstorm",
        96: "thunderstorm with slight hail",
        99: "thunderstorm with heavy hail",
    }

    try:
        return mapping.get(int(code), "unknown conditions")
    except Exception:
        return "unknown conditions"


def get_live_weather_context(query):
    location = _extract_location(query, fallback="Chennai")
    geo = _geocode_location(location)

    if not geo:
        return ""

    latitude = geo.get("latitude")
    longitude = geo.get("longitude")

    if latitude is None or longitude is None:
        return ""

    params = urlencode({
        "latitude": latitude,
        "longitude": longitude,
        "current": (
            "temperature_2m,apparent_temperature,weather_code,wind_speed_10m"
        ),
        "timezone": "auto",
    })

    data = _http_json(
        "https://api.open-meteo.com/v1/forecast?" + params
    )

    current = data.get("current") or {}

    place = ", ".join(
        part
        for part in (geo.get("name", ""), geo.get("country", ""))
        if part
    )

    return (
        "VERIFIED LIVE WEATHER\n"
        f"Location: {place}\n"
        f"Temperature: {current.get('temperature_2m')} °C\n"
        f"Feels like: {current.get('apparent_temperature')} °C\n"
        f"Condition: {_weather_description(current.get('weather_code'))}\n"
        f"Wind speed: {current.get('wind_speed_10m')} km/h\n"
        f"Observed time: {current.get('time', '')}\n"
        "Source: Open-Meteo live weather data\n"
        "Instruction: Use these live values rather than model memory."
    )


# =========================================================
# Search queries
# =========================================================


def build_search_queries(query):
    """
    Create short, focused search variants.

    Avoid sending the user's full instruction
    sentence to the search engine.
    """

    query = query.strip()
    lower_query = query.lower()

    queries = []

    # --------------------------------
    # AI news
    # --------------------------------
    if is_news_query(query):

        if (
            re.search(r"\bai\b", lower_query)
            or "artificial intelligence"
            in lower_query
        ):

            queries.extend([
                "AI news",
                "artificial intelligence news",
                "OpenAI Anthropic Google AI news",
                "AI technology news Reuters",
                "AI announcements",
            ])

        else:

            topic_words = list(
                core_topic_words(
                    query
                )
            )

            topic = " ".join(
                topic_words[:5]
            ).strip()

            if not topic:
                topic = query

            queries.extend([
                topic,
                f"{topic} news",
                f"{topic} latest",
                f"{topic} Reuters",
                f"{topic} official",
            ])

    # --------------------------------
    # Current factual query
    # --------------------------------
    elif is_current_fact_query(query):

        q = query.lower()

        # Tamil Nadu Chief Minister
        if (
            "cm of tamilnadu" in q
            or "cm of tamil nadu" in q
            or "chief minister of tamilnadu" in q
            or "chief minister of tamil nadu" in q
        ):
            queries.extend([
                "current Chief Minister of Tamil Nadu official",
                "Tamil Nadu Chief Minister official government",
                "site:tn.gov.in Chief Minister Tamil Nadu",
                "site:lokbhavan.tn.gov.in Chief Minister Tamil Nadu",
                "current Tamil Nadu Chief Minister Reuters",
            ])

        else:
            queries.extend([
                query,
                f"{query} official",
                f"{query} government official",
                f"{query} latest verified",
                f"{query} Reuters",
            ])

    # --------------------------------
    # Normal search
    # --------------------------------
    else:

        queries.append(
            query
        )

        programming_terms = [
            "python",
            "java",
            "javascript",
            "c++",
            "programming",
            "decorator",
            "function",
            "library",
            "framework",
        ]

        if any(
            term in lower_query
            for term in programming_terms
        ):
            queries.extend([
                f"{query} official documentation",
                f"{query} tutorial",
            ])

    # --------------------------------
    # Deduplicate
    # --------------------------------

    unique = []
    seen = set()

    for item in queries:

        cleaned = item.strip()

        if not cleaned:
            continue

        key = cleaned.lower()

        if key not in seen:

            seen.add(
                key
            )

            unique.append(
                cleaned
            )

    return unique[:5]


# =========================================================
# Scoring
# =========================================================


def source_score(url):
    domain = normalize_domain(url)

    if not domain:
        return 0

    if domain in TRUSTED_DOMAINS:
        return TRUSTED_DOMAINS[domain]

    for trusted, score in TRUSTED_DOMAINS.items():
        if domain.endswith("." + trusted):
            return score

    if domain in AGGREGATOR_DOMAINS:
        return AGGREGATOR_DOMAINS[domain]

    for aggregator, penalty in AGGREGATOR_DOMAINS.items():
        if domain.endswith("." + aggregator):
            return penalty

    return 0


def attribution_score(result):
    combined = (
        result.get("title", "") + " " + result.get("body", "")
    ).lower()

    score = 0

    if "reuters" in combined:
        score += 6

    if "associated press" in combined or " ap " in f" {combined} ":
        score += 6

    if "bbc" in combined:
        score += 3

    return score


def topic_match_score(query, result):
    topic_words = core_topic_words(query)

    if not topic_words:
        return 0

    title = result.get("title", "").lower()
    body = result.get("body", "").lower()
    combined = title + " " + body

    title_matches = sum(1 for word in topic_words if word in title)
    total_matches = sum(1 for word in topic_words if word in combined)

    if total_matches == 0:
        return -100

    if total_matches == 1 and title_matches == 0:
        return -40

    if total_matches == 1:
        return 4

    if total_matches == 2:
        return 10

    return 18


def relevance_score(query, result):
    """
    Score how strongly the result matches the user's topic.
    Gives extra weight to real AI-focused stories.
    """

    query_words = tokenize(query)

    if not query_words:
        return 0

    title = result.get("title", "")
    body = result.get("body", "")

    title_words = tokenize(title)
    body_words = tokenize(body)

    title_matches = query_words & title_words
    body_matches = query_words & body_words

    score = (
        len(title_matches) * 6
        + len(body_matches) * 2
    )

    coverage = len(
        query_words & (title_words | body_words)
    ) / max(
        len(query_words),
        1,
    )

    if coverage >= 0.8:
        score += 8

    elif coverage >= 0.5:
        score += 4

    elif coverage < 0.2:
        score -= 6


    # =====================================================
    # EXTRA AI NEWS RELEVANCE
    # =====================================================

    query_lower = query.lower()

    if (
        re.search(r"\bai\b", query_lower)
        or "artificial intelligence" in query_lower
    ):

        title_lower = title.lower()
        body_lower = body.lower()

        ai_terms = [
            "ai",
            "artificial intelligence",
            "openai",
            "chatgpt",
            "anthropic",
            "claude",
            "gemini",
            "deepmind",
            "llm",
            "large language model",
            "machine learning",
        ]

        title_ai_matches = sum(
            1
            for term in ai_terms
            if term in title_lower
        )

        body_ai_matches = sum(
            1
            for term in ai_terms
            if term in body_lower
        )

        # Strong AI focus in title
        if title_ai_matches >= 2:
            score += 18

        elif title_ai_matches == 1:
            score += 10

        # Some AI focus in body
        if body_ai_matches >= 2:
            score += 6

        elif body_ai_matches == 1:
            score += 2

        # Weak / incidental AI mention
        if (
            title_ai_matches == 0
            and body_ai_matches <= 1
        ):
            score -= 20


    return score


def _parse_result_date(date_value):
    if not date_value:
        return None

    try:
        cleaned = str(date_value).strip().replace("Z", "+00:00")
        published = datetime.fromisoformat(cleaned)

        if published.tzinfo is None:
            published = published.replace(tzinfo=timezone.utc)

        return published.astimezone(timezone.utc)

    except Exception:
        try:
            published = parsedate_to_datetime(str(date_value).strip())
            if published.tzinfo is None:
                published = published.replace(tzinfo=timezone.utc)
            return published.astimezone(timezone.utc)
        except Exception:
            return None


def freshness_score(result, news_query=False, horizon=None):
    if not news_query:
        return 0

    published = _parse_result_date(result.get("date"))

    if published is None:
        return -8 if horizon is not None else -2

    now = datetime.now(timezone.utc)
    age = now - published
    age_hours = age.total_seconds() / 3600

    if horizon is not None and age > horizon:
        return -100

    if age_hours <= 24:
        return 18
    if age_hours <= 72:
        return 12
    if age_hours <= 168:
        return 8
    if age_hours <= 720:
        return 2

    return -10


def article_score(result):
    url = result.get("href") or result.get("url") or ""
    lower_url = url.lower()

    bad_url_markers = (
        "globenewswire",
        "prnewswire",
        "businesswire",
        "einpresswire",
        "sponsored-content",
        "deals",
        "deal",
        "coupon",
        "promo",
    )

    if any(
        marker in lower_url
        for marker in bad_url_markers
    ):
        return -100

    title = result.get("title", "").lower()
    bad_title_phrases = [
        "sponsored",
        "deal",
        "discount",
        "lifetime access",
        "for just $",
        "save $",
        "buy now",
        "members-only event",
    ]

    if any(
        phrase in title
        for phrase in bad_title_phrases
    ):
        return -100
    parsed = urlparse(url)
    path = parsed.path.lower().strip("/")

    if not path:
        return -10

    parts = [item for item in path.split("/") if item]
    score = 0

    if any(
        indicator in path
        for indicator in (
            "article", "articleshow", "story", "2026/", "2025/",
            ".html", ".htm", ".cms",
        )
    ):
        score += 8

    if len(parts) >= 4:
        score += 5
    elif len(parts) == 3:
        score += 3

    category_paths = {
        "news", "latest", "technology", "tech", "ai",
        "artificial-intelligence", "gadgets-news", "topics",
        "section", "category",
    }

    if len(parts) <= 3 and parts and parts[-1] in category_paths:
        score -= 12

    if any(
        phrase in title
        for phrase in (
            "latest news", "artificial intelligence news", "tech news",
            "technology news", "news homepage", "news, videos and pictures",
            "latest updates",
        )
    ):
        score -= 10

    return score


def primary_source_score(query, result):
    domain = normalize_domain(
        result.get("href") or result.get("url") or ""
    )

    query_lower = query.lower()

    company_domains = {
        "openai": ["openai.com"],
        "anthropic": ["anthropic.com"],
        "google": ["google.com", "deepmind.google", "ai.google"],
        "deepmind": ["deepmind.google"],
        "microsoft": ["microsoft.com"],
        "meta": ["meta.com"],
        "nvidia": ["nvidia.com"],
        "python": ["python.org", "docs.python.org", "peps.python.org"],
    }

    for keyword, domains in company_domains.items():
        if keyword not in query_lower:
            continue

        if any(
            domain == item or domain.endswith("." + item)
            for item in domains
        ):
            return 8

    if is_current_fact_query(query):
        if domain.endswith(".gov.in") or domain == "india.gov.in":
            return 12

    return 0


# =========================================================
# Duplicate removal / verification
# =========================================================


def remove_duplicates(results):
    cleaned = []
    seen_urls = set()
    seen_titles = set()

    for item in results:
        url = normalize_url(
            item.get("href") or item.get("url") or ""
        )

        title = item.get("title", "").strip().lower()

        if not url or url in seen_urls:
            continue

        if title and title in seen_titles:
            continue

        seen_urls.add(url)

        if title:
            seen_titles.add(title)

        item["_normalized_url"] = url
        cleaned.append(item)

    return cleaned


def story_similarity(result_a, result_b):
    title_a = tokenize(result_a.get("title", ""))
    title_b = tokenize(result_b.get("title", ""))
    body_a = tokenize(result_a.get("body", "") or result_a.get("summary", ""))
    body_b = tokenize(result_b.get("body", "") or result_b.get("summary", ""))

    title_similarity = 0
    body_similarity = 0

    if title_a and title_b:
        title_similarity = len(title_a & title_b) / max(len(title_a | title_b), 1)

    if body_a and body_b:
        body_similarity = len(body_a & body_b) / max(len(body_a | body_b), 1)

    return title_similarity * 0.7 + body_similarity * 0.3


def calculate_verification_scores(results):
    verification = {id(item): 0 for item in results}

    for i in range(len(results)):
        domain_a = normalize_domain(
            results[i].get("href") or results[i].get("url") or ""
        )

        supporting_domains = set()

        for j in range(len(results)):
            if i == j:
                continue

            domain_b = normalize_domain(
                results[j].get("href") or results[j].get("url") or ""
            )

            if not domain_b or domain_a == domain_b:
                continue

            if story_similarity(results[i], results[j]) >= 0.35:
                supporting_domains.add(domain_b)

        support_count = len(supporting_domains)

        if support_count >= 3:
            verification[id(results[i])] = 8
        elif support_count == 2:
            verification[id(results[i])] = 5
        elif support_count == 1:
            verification[id(results[i])] = 2

    return verification


def total_score(
    original_query,
    result,
    verification_score,
    news_query=False,
    horizon=None,
):
    url = result.get("href") or result.get("url") or ""

    topic_score = topic_match_score(original_query, result)

    if topic_score <= -50:
        return -100

    fresh = freshness_score(
        result,
        news_query,
        horizon=horizon,
    )

    if fresh <= -100:
        return -100

    return (
        topic_score * 2
        + relevance_score(original_query, result) * 2
        + source_score(url) * 3
        + attribution_score(result) * 2
        + fresh * 3
        + article_score(result)
        + primary_source_score(original_query, result) * 3
        + verification_score * 2
    )


# =========================================================
# DDGS
# =========================================================


def _ddgs_timelimit(query):
    q = query.lower()

    if "today" in q or "last 24 hours" in q or "past 24 hours" in q:
        return "d"

    if "this month" in q or "last month" in q:
        return "m"

    return "w"


def search_single_query(
    query,
    news_query,
    results_per_query=8,
):
    """
    Perform one web search.

    For news:
    1. Try DDGS news search.
    2. If no news results are returned,
       fall back to text search.
    """

    try:

        ddgs = DDGS(
            timeout=10
        )

        # =================================
        # NEWS SEARCH
        # =================================

        if news_query:

            try:

                results = list(
                    ddgs.news(
                        query=query,
                        region="in-en",
                        safesearch="moderate",
                        timelimit=_ddgs_timelimit(
                            query
                        ),
                        max_results=results_per_query,
                        backend="auto",
                    )
                )

            except Exception as error:

                print(
                    f"News search failed for "
                    f"'{query}': {error}"
                )

                results = []

            if results:
                return results

            # --------------------------------
            # Fallback to text search
            # --------------------------------

            print(
                f"⚠️ No news results for "
                f"'{query}'. Trying text search..."
            )

            text_results = list(
                ddgs.text(
                    query=query,
                    region="in-en",
                    safesearch="moderate",
                    max_results=results_per_query,
                    backend="auto",
                )
            )

            # Text results normally have no date.
            # Mark them so strict freshness filtering
            # can treat them cautiously.
            for item in text_results:

                item.setdefault(
                    "date",
                    ""
                )

            return text_results

        # =================================
        # NORMAL WEB SEARCH
        # =================================

        return list(
            ddgs.text(
                query=query,
                region="in-en",
                safesearch="moderate",
                max_results=results_per_query,
                backend="auto",
            )
        )

    except Exception as error:

        print(
            f"Search failed for "
            f"'{query}': {error}"
        )

        return []


def parallel_search(original_query, queries, results_per_query=12):
    news_query = is_news_query(original_query)
    all_results = []

    max_workers = min(len(queries), 6)

    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = {
            executor.submit(
                search_single_query,
                query,
                news_query,
                results_per_query,
            ): query
            for query in queries
        }

        for future in as_completed(futures):
            search_query = futures[future]

            try:
                results = future.result()

                for result in results:
                    result["_search_query"] = search_query

                all_results.extend(results)

            except Exception as error:
                print(
                    "Parallel search error "
                    f"for '{search_query}': {error}"
                )

    return all_results


# =========================================================
# Ranking / formatting
# =========================================================
def is_today_in_india(date_value):
    """
    Return True only if the article was published
    on today's calendar date in India.
    """

    published = _parse_result_date(
        date_value
    )

    if published is None:
        return False

    india_timezone = ZoneInfo(
        "Asia/Kolkata"
    )

    published_india = (
        published.astimezone(
            india_timezone
        )
    )

    today_india = (
        datetime.now(
            india_timezone
        ).date()
    )

    return (
        published_india.date()
        == today_india
    )

def rank_results(original_query, results, max_results=6):
    results = remove_duplicates(results)

    if not results:
        return []

    news_query = is_news_query(original_query)
    horizon = requested_news_horizon(original_query) if news_query else None

    verification_scores = calculate_verification_scores(results)
    filtered_results = []

    for result in results:
        url = result.get("href") or result.get("url") or ""
        domain = normalize_domain(url)
        title = result.get("title", "").lower()
        path = urlparse(url).path.lower()

        # Reject weak aggregators for news
        if news_query and domain in {
            "aol.com",
            "yahoo.com",
            "finance.yahoo.com",
            "tech.yahoo.com",
            "msn.com",
            "news.google.com",
        }:
            continue

        # Reject tag/category/index pages
        if news_query and any(
            marker in path
            for marker in (
                "/tag/",
                "/tags/",
                "/topic/",
                "/topics/",
                "/category/",
                "/categories/",
            )
        ):
            continue

        # Reject obvious category-style titles
        if news_query and any(
            phrase in title
            for phrase in (
                "latest news",
                "all news",
                "latest updates",
                "artificial intelligence news",
            )
        ):
            continue
        if news_query:

            # "Today" means today's actual
            # calendar date in India.
            if "today" in original_query.lower():

                if not is_today_in_india(
                    result.get("date")
                ):
                    continue

            elif horizon is not None:

                published = _parse_result_date(
                    result.get("date")
                )

                if published is None:
                    continue

                if (
                    datetime.now(timezone.utc)
                    - published
                    > horizon
                ):
                    continue

        result["_verification_score"] = verification_scores.get(id(result), 0)
        result["_score"] = total_score(
            original_query,
            result,
            result["_verification_score"],
            news_query,
            horizon=horizon,
        )

        if result["_score"] <= -50:
            continue

        filtered_results.append(result)

    if not filtered_results:
        return []

    ranked = sorted(
        filtered_results,
        key=lambda item: item["_score"],
        reverse=True,
    )

    selected = []
    domain_counts = {}

    for result in ranked:
        domain = normalize_domain(
            result.get("href") or result.get("url") or ""
        )

        count = domain_counts.get(domain, 0)

        if count >= 2:
            continue

        selected.append(result)
        domain_counts[domain] = count + 1

        if len(selected) >= max_results:
            break

    return selected


def format_results(results):
    formatted = []

    for index, item in enumerate(results, start=1):
        title = item.get("title", "No title")
        body = item.get("body", "No description")
        url = item.get("href") or item.get("url") or ""
        domain = normalize_domain(url)
        date_value = item.get("date", "")

        block = (
            f"Result {index}\n"
            f"Title: {title}\n"
            f"Source: {domain}\n"
        )

        if date_value:
            block += f"Date: {date_value}\n"

        block += (
            f"Summary: {body}\n"
            f"Link: {url}"
        )

        formatted.append(block)

    return "\n\n".join(formatted)


# =========================================================
# Main API
# =========================================================
def requested_result_count(query, default=5):
    """
    Detect how many results the user requested.
    Example:
    'latest 3 AI news stories' -> 3
    """

    match = re.search(
        r"\b(?:latest|top|give|show)?\s*(\d+)\s+",
        query.lower(),
    )

    if match:
        try:
            count = int(match.group(1))

            if 1 <= count <= 10:
                return count

        except Exception:
            pass

    return default

def _plan_timelimit(freshness_days):
    if freshness_days is None:
        return None
    if freshness_days <= 1:
        return "d"
    if freshness_days <= 7:
        return "w"
    if freshness_days <= 31:
        return "m"
    return None


def _search_from_plan(search_query, search_mode, freshness_days, limit):
    ddgs = DDGS(timeout=10)
    if search_mode == "news":
        return list(ddgs.news(
            query=search_query,
            region="in-en",
            safesearch="moderate",
            timelimit=_plan_timelimit(freshness_days),
            max_results=limit,
            backend="auto",
        ))
    return list(ddgs.text(
        query=search_query,
        region="in-en",
        safesearch="moderate",
        max_results=limit,
        backend="auto",
    ))


def _matches_required_concepts(result, required_concepts):
    """Check model-supplied topic concepts, not words detected from the query."""
    if not required_concepts:
        return True

    combined = " ".join((
        str(result.get("title", "")),
        str(result.get("body", "") or result.get("summary", "")),
    ))
    result_words = tokenize(combined)

    for concept in required_concepts:
        concept_words = tokenize(str(concept))
        if concept_words and concept_words.issubset(result_words):
            return True

    return False


def search_web(query, max_results=6, search_plan=None):
    """
    Execute the AI-generated semantic plan without keyword-based routing.
    """

    query = query.strip()

    if not query:
        return "Please provide something to search for."

    plan = search_plan if isinstance(search_plan, dict) else {}
    search_mode = plan.get("search_mode", "web")
    if search_mode not in {"news", "web"}:
        search_mode = "web"
    needs_freshness = bool(plan.get("needs_freshness"))
    freshness_days = plan.get("freshness_days") if needs_freshness else None
    try:
        freshness_days = int(freshness_days) if freshness_days is not None else None
    except (TypeError, ValueError):
        freshness_days = None

    planned_queries = plan.get("search_queries")
    if not isinstance(planned_queries, list):
        planned_queries = []
    planned_queries = [
        str(item).strip() for item in planned_queries[:5] if str(item).strip()
    ] or [query]

    required_concepts = plan.get("required_concepts")
    if not isinstance(required_concepts, list):
        required_concepts = []
    required_concepts = [
        str(item).strip()
        for item in required_concepts[:8]
        if str(item).strip()
    ]

    all_results = []
    with ThreadPoolExecutor(max_workers=min(len(planned_queries), 5)) as executor:
        futures = {
            executor.submit(
                _search_from_plan, item, search_mode, freshness_days, 12
            ): item
            for item in planned_queries
        }
        for future in as_completed(futures):
            try:
                all_results.extend(future.result())
            except Exception as error:
                print(f"Search failed for '{futures[future]}': {error}")

    if not all_results and search_mode == "news":
        for item in planned_queries:
            try:
                all_results.extend(_search_from_plan(item, "web", None, 10))
            except Exception as error:
                print(f"Text fallback failed for '{item}': {error}")

    if not all_results:
        return ""

    unique_results = remove_duplicates(all_results)

    if not unique_results:
        return ""

    unique_results = [
        result
        for result in unique_results
        if _matches_required_concepts(result, required_concepts)
    ]

    if not unique_results:
        return ""

    if needs_freshness and freshness_days is not None:
        cutoff = datetime.now(timezone.utc) - timedelta(days=freshness_days)
        unique_results = [
            result
            for result in unique_results
            if (
                _parse_result_date(result.get("date")) is not None
                and _parse_result_date(result.get("date")) >= cutoff
            )
        ]

    if not unique_results:
        return ""

    for result in unique_results:
        url = result.get("href") or result.get("url") or ""
        fresh = freshness_score(
            result,
            news_query=needs_freshness,
            horizon=(
                timedelta(days=freshness_days)
                if freshness_days is not None
                else None
            ),
        )
        result["_score"] = (
            relevance_score(query, result) * 2
            + source_score(url) * 2
            + fresh * 3
        )

    best_results = sorted(
        unique_results,
        key=lambda item: item.get("_score", 0),
        reverse=True,
    )[:max_results]

    print(f"✅ Returning {len(best_results)} web results")

    return format_results(best_results)
