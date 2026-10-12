import os
import json
import urllib.request
from langchain_core.tools import tool
from langchain_google_genai import ChatGoogleGenerativeAI
from langgraph.prebuilt import create_react_agent

@tool
def get_share_price(ticker: str) -> str:
    """
    Fetches the latest share price for a given stock ticker symbol.
    Examples of ticker symbols:
    - Apple: 'AAPL'
    - Microsoft: 'MSFT'
    - Tesla: 'TSLA'
    - Amazon: 'AMZN'
    - Reliance (NSE India): 'RELIANCE.NS'
    """
    try:
        url = f"https://query1.finance.yahoo.com/v8/finance/chart/{ticker.strip().upper()}"
        req = urllib.request.Request(
            url,
            headers={"User-Agent": "Mozilla/5.0"}
        )
        with urllib.request.urlopen(req) as response:
            data = json.loads(response.read().decode())

        result = data["chart"]["result"][0]
        price = result["meta"]["regularMarketPrice"]
        currency = result["meta"]["currency"]
        return f"The latest price for {ticker.upper()} is {price:.2f} {currency}."
    except Exception as e:
        return f"Could not fetch stock price for '{ticker}': {str(e)}"

tools = [get_share_price]

os.environ["GOOGLE_API_KEY"] = ""
llm = ChatGoogleGenerativeAI(model="gemini-2.5-flash", temperature=0)
agent_executor = create_react_agent(llm, tools)

print("Sending query to the agent...\n")
response = agent_executor.invoke({
    "messages": [("user", "What's the share price for Apple?")]
})

print("\nFinal Answer:")
print(response["messages"][-1].content)
