from flask import Flask, jsonify
import random

app = Flask(__name__)

quotes = [
    "The future belongs to those who believe in the beauty of their dreams.",
]

@app.route('/')
def home():
    return jsonify({
        "message": "Welcome to the Alafia API", 
        "endpoints": ["/health", "/quote", "/quotes"]
    })

@app.route('/health')
def health():
    return jsonify({"status": "healthy"})

@app.route('/quote')
def get_quote():
    return jsonify({
        "quote": random.choice(quotes)
    })

@app.route('/quotes')
def get_quotes():
    return jsonify({
        "quotes": quotes
    })

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, debug=False)
