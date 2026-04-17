# PL Lineup Predictor

A Spring Boot web application that predicts starting lineups and match outcomes for upcoming Premier League fixtures.

## Prompts Used to Build This Project

1. **"Create a spring boot app that can be easily deployed to render. Provide a docker file. The app should predict the lineups for upcoming matches in the Premier league"**

2. **"Half these players are inexperienced or injured"**
   - Improved the lineup prediction algorithm with a weighted scoring system favouring prime-age players (25-30), first-team shirt numbers (1-11), and positional fit for the manager's typical formation.

3. **"Make the site look more fancy"**
   - Full UI redesign with glassmorphism cards, animated gradient backgrounds, position badges, responsive layout, and polished typography.

4. **"Add a scrolling title showing upcoming games"**
   - Added a sticky scrolling ticker bar at the top of every page displaying upcoming fixtures with team crests and kick-off times. Pauses on hover.

5. **"Show a prediction of which team will win and the likely score"**
   - Added match outcome prediction with predicted scoreline, win/draw/loss outcome badge, and confidence percentage based on squad attack vs defence strength analysis.

6. **"Add all prompts to a readme file"**
   - This README.

## Features

- Predicted starting XI for both teams in every upcoming PL match
- Predicted match score and outcome with confidence rating
- Scrolling ticker bar with upcoming fixtures
- Glassmorphism UI with animated backgrounds
- JSON API endpoints (`/api/matches`, `//api/predict/{matchId}`)
- Dockerised for easy deployment to Render

## Tech Stack

- Java 21, Spring Boot 3.3.4, Thymeleaf
- football-data.org API (free tier)
- Multi-stage Docker build (eclipse-temurin:21)

## Running Locally

```bash
export FOOTBALL_DATA_API_KEY=your_key_here
mvn spring-boot:run
```

Get a free API key at [football-data.org](https://www.football-data.org/client/register).

## Deploying to Render

1. Push to GitHub
2. Create a new Web Service on Render
3. Set environment variable `FOOTBALL_DATA_API_KEY`
4. Render auto-detects the Dockerfile
